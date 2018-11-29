package scala.meta.internal.mtags

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import scala.collection.concurrent.TrieMap
import scala.meta.inputs.Input
import scala.meta.internal.io.PathIO
import scala.meta.internal.io._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

/**
 * An implementation of GlobalSymbolIndex with fast indexing and low memory usage.
 *
 * Fast indexing is enabled by ScalaToplevelMtags, a custom parser that extracts
 * only toplevel symbols from a Scala source file. Java source files don't need indexing
 * because their file location can be inferred from the symbol with the limitation
 * that it doesn't work for Java source files with multiple package-private top-level classes.
 *
 * Low memory usage is enabled by only storing "non-trivial toplevel" symbols.
 * A symbol is "toplevel" when its owner is a package. A symbol is "non-trivial"
 * when it doesn't match the path of the file it's defined in, for example `Some#`
 * in Option.scala is non-trivial while `Option#` in Option.scala is trivial.
 *
 * @param toplevels keys are non-trivial toplevel symbols and values are the file
 *                  the symbols are defined in.
 * @param definitions keys are global symbols and the values are the files the symbols
 *                    are defined in. Difference between toplevels and definitions
 *                    is that toplevels contains only symbols generated by ScalaToplevelMtags
 *                    while definitions contains only symbols generated by ScalaMtags.
 */
final case class OnDemandSymbolIndex(
    toplevels: TrieMap[String, AbsolutePath] = TrieMap.empty,
    definitions: TrieMap[String, AbsolutePath] = TrieMap.empty,
    onError: PartialFunction[Throwable, Unit] = PartialFunction.empty
) extends GlobalSymbolIndex {
  val mtags = new Mtags
  private val sourceJars = new ClasspathLoader()
  var indexedSources = 0L
  def close(): Unit = sourceJars.close()
  private val onErrorOption = onError.andThen(_ => None)

  override def definition(symbol: Symbol): Option[SymbolDefinition] = {
    try findSymbolDefinition(symbol, symbol)
    catch onErrorOption
  }

  // Traverses all source files in the given jar file and records
  // all non-trivial toplevel Scala symbols.
  override def addSourceJar(jar: AbsolutePath): Unit = tryRun {
    if (sourceJars.addEntry(jar)) {
      FileIO.withJarFileSystem(jar, create = false) { root =>
        ListFiles.foreach(root) { source =>
          if (source.toLanguage.isScala) {
            addSourceFile(source, None)
          }
        }
      }
    }
  }

  // Enters nontrivial toplevel symbols for Scala source files.
  // All other symbols can be inferred on the fly.
  override def addSourceFile(
      source: AbsolutePath,
      sourceDirectory: Option[AbsolutePath]
  ): Unit = tryRun {
    indexedSources += 1
    val path = sourceDirectory match {
      case Some(directory) =>
        source.toRelative(directory).toURI(false).toString()
      case _ => source.toString()
    }
    val text = FileIO.slurp(source, StandardCharsets.UTF_8)
    val input = Input.VirtualFile(path, text)
    val sourceToplevels = mtags.toplevels(input)
    sourceToplevels.foreach { toplevel =>
      if (!isTrivialToplevelSymbol(path, toplevel)) {
        toplevels(toplevel) = source
      }
    }
  }

  private def tryRun(thunk: => Unit): Unit =
    try thunk
    catch onError

  // Returns true if symbol is com/foo/Bar# and path is /com/foo/Bar.scala
  // Such symbols are "trivial" because their definition location can be computed
  // on the fly.
  private def isTrivialToplevelSymbol(path: String, symbol: String): Boolean = {
    val pathBuffer =
      CharBuffer.wrap(path).subSequence(1, path.length - ".scala".length)
    val symbolBuffer =
      CharBuffer.wrap(symbol).subSequence(0, symbol.length - 1)
    pathBuffer.equals(symbolBuffer)
  }

  // similar as addSourceFile except indexes all global symbols instead of
  // only non-trivial toplevel symbols.
  private def addMtagsSourceFile(file: AbsolutePath): Unit = {
    val docs: s.TextDocuments = PathIO.extension(file.toNIO) match {
      case "scala" | "java" =>
        val language = file.toLanguage
        val input = file.toInput
        val document = mtags.index(language, input)
        s.TextDocuments(List(document))
      case _ =>
        s.TextDocuments(Nil)
    }
    if (docs.documents.nonEmpty) {
      addTextDocuments(file, docs)
    }
  }

  // Records all global symbol definitions.
  private def addTextDocuments(
      file: AbsolutePath,
      docs: s.TextDocuments
  ): Unit = {
    docs.documents.foreach { document =>
      document.occurrences.foreach { occ =>
        if (occ.symbol.isGlobal && occ.role.isDefinition) {
          definitions.put(occ.symbol, file)
        } else {
          // do nothing, we only care about global symbol definitions.
        }
      }
    }
  }

  /**
   * Returns the file where symbol is defined, if any.
   *
   * Uses two strategies to recover from missing symbol definitions:
   * - try to enter the toplevel symbol definition, then lookup symbol again.
   * - if the symbol is synthetic, for examples from a case class of macro annotation,
   *  fall back to related symbols from the enclosing class, see `DefinitionAlternatives`.
   *
   * @param querySymbol The original symbol that was queried by the user.
   * @param symbol The symbol that
   * @return
   */
  private def findSymbolDefinition(
      querySymbol: Symbol,
      symbol: Symbol
  ): Option[SymbolDefinition] = {
    if (!definitions.contains(symbol.value)) {
      // Fallback 1: enter the toplevel symbol definition
      val toplevel = symbol.toplevel
      toplevels.get(toplevel.value) match {
        case Some(file) =>
          addMtagsSourceFile(file)
        case _ =>
          loadFromSourceJars(trivialPaths(toplevel)).foreach(addMtagsSourceFile)
      }
    }
    if (!definitions.contains(symbol.value)) {
      // Fallback 2: guess related symbols from the enclosing class.
      DefinitionAlternatives(symbol)
        .flatMap(alternative => findSymbolDefinition(querySymbol, alternative))
        .headOption
    } else {
      definitions.get(symbol.value).map { path =>
        SymbolDefinition(
          querySymbol = querySymbol,
          definitionSymbol = symbol,
          path
        )
      }
    }
  }

  // Returns the first path that resolves to a file.
  private def loadFromSourceJars(paths: List[String]): Option[AbsolutePath] = {
    paths match {
      case Nil => None
      case head :: tail =>
        sourceJars.load(head) match {
          case Some(file) => Some(file)
          case _ => loadFromSourceJars(tail)
        }
    }
  }

  // Returns relative file paths for trivial toplevel symbols, example:
  // Input:  scala/collection/immutable/List#
  // Output: scala/collection/immutable/List.scala
  //         scala/collection/immutable/List.java
  private def trivialPaths(toplevel: Symbol): List[String] = {
    val noExtension = toplevel.value.stripSuffix(".").stripSuffix("#")
    List(
      noExtension + ".scala",
      noExtension + ".java"
    )
  }

}