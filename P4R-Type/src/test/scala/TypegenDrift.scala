package p4rtype.consumertest

/** Shared drift check: the committed generated types must be exactly what
  * `typegen` emits today.
  *
  * Extracted because `QuackMppTypegenSuite` and `MatchKindsSuite` had a verbatim
  * copy each, and a third arrives with the next fixture. Both copies also read
  * with a bare `Source.fromFile(...).mkString`, leaking the handle until GC —
  * the same unclosed-`Source` pattern already fixed in `typegen`'s CLI.
  *
  * It calls the `generate` library entry point directly. This used to be a shell
  * pipeline in CI that ran `runMain parseP4info`, sed'd the types out of sbt's
  * log and diffed them. That went green locally and failed in real CI: sbt
  * colourises its output there, so `[success]` is actually
  * `[<ESC>[32msuccess<ESC>[0m]` and the sed meant to cut sbt's trailer never
  * matched. Calling `generate` removes the log parsing entirely.
  */
object TypegenDrift {

  /** @param resource the p4info on the test classpath, e.g. `"foo.p4info.json"`
    * @param sourcePath the committed generated file, relative to the project
    *                   base directory
    * @param packageName the package `typegen` should declare
    */
  def check(resource : String, sourcePath : String, packageName : String)(using
      loc : munit.Location) : Unit =
    val p4info = scala.util.Using.resource(scala.io.Source.fromResource(resource))(_.mkString)

    // user.dir is the project base directory because build.sbt sets
    // `Test / fork := true` — sbt gives a forked test JVM
    // workingDirectory = baseDirectory. Unforked it would inherit sbt's cwd.
    val fixture = java.io.File(System.getProperty("user.dir"), sourcePath)
    assert(fixture.isFile, s"fixture not found at ${fixture.getAbsolutePath}")
    val committed = scala.util.Using.resource(scala.io.Source.fromFile(fixture))(_.mkString)

    typegen.generate(p4info, packageName) match
      case Left(err)  => munit.Assertions.fail(s"typegen failed: $err")
      case Right(out) =>
        munit.Assertions.assertEquals(
          out, committed,
          s"typegen output drifted from $sourcePath — regenerate with " +
          "container/p4rt.sh gen-types"
        )
}
