package tests.pc

import tests.BasePCSuite
import tests.RangeReplace
import tests.TestHovers
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.mtags.MtagsEnrichments._

abstract class BaseHoverSuite
    extends BasePCSuite
    with TestHovers
    with RangeReplace {

  def check(
      name: String,
      original: String,
      expected: String,
      includeRange: Boolean = false,
      automaticPackage: Boolean = true
  ): Unit = {
    test(name) {
      val filename = "Hover.scala"
      val pkg = scala.meta.Term.Name(name).syntax
      val noRange = original
        .replaceAllLiterally("<<", "")
        .replaceAllLiterally(">>", "")
      val packagePrefix =
        if (automaticPackage) s"package $pkg\n"
        else ""
      val codeOriginal = packagePrefix + noRange
      val (code, offset) = params(codeOriginal, filename)
      val hover = pc.hover(
        CompilerOffsetParams(filename, code, offset)
      )
      val obtained: String = renderAsString(code, hover.asScala, includeRange)
      assertNoDiff(obtained, expected)
      for {
        h <- hover.asScala
        range <- Option(h.getRange)
      } {
        val base = codeOriginal.replaceAllLiterally("@@", "")
        val withRange = replaceInRange(base, range)
        assertNoDiff(
          withRange,
          packagePrefix + original.replaceAllLiterally("@@", ""),
          "Invalid range"
        )
      }
    }
  }

}
