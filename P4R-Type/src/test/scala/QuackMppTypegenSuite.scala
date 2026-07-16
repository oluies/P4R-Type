// This suite deliberately lives in a NAMED package, not the empty package.
// `generate` is meant to be called by a downstream build (QuackMPP's Mill build
// compiles into `build`/`millbuild`), and the empty package is not importable
// from a named one. A test in the empty package would resolve `generate` even if
// no real consumer could, so it would prove nothing about the thing that matters.
package p4rtype.consumertest

import p4rtype.{Exact, TableEntry, bytes}
import quackmpp.{TableMatchFields, TableAction, ActionParams}
import typegen.generate

/** Pins the guarantee QuackMPP spec 003 depends on: the Scala 3 types generated
  * by `typegen` from a p4c/v1model p4info make a renamed or removed P4 match
  * field a *compile* error in the controller, not a runtime failure.
  *
  * The types under test are generated from
  * `src/test/resources/quackmpp_exchange.p4info.json` — emitted by real p4c from
  * `examples/src/main/p4/quackmpp.p4` — which declares an
  * exact-match table `QuackMPP.exchange` keyed on `meta.quack.bucket`.
  *
  * Note on how these tests are written: the negative cases assert only that the
  * snippet fails to compile, deliberately *not* that the error text contains a
  * particular field name. Two reasons. First, `compileErrors` echoes the snippet
  * source into its message, and `"meta.quack.bucket_renamed"` contains
  * `"meta.quack.bucket"`, so a substring assertion would pass trivially. Second,
  * the rename surfaces as a cascading "match type could not be fully reduced"
  * error on `ActionParams`, not as a message naming the field. What makes the
  * negative tests meaningful is instead the control test below, which proves
  * `compileErrors` reports *nothing* for the correct snippet — so a failure in
  * the negative cases is caused by the mutation, not by the harness always
  * failing.
  */
class QuackMppTypegenSuite extends munit.FunSuite {

  /** Drift check: the committed fixture must be exactly what typegen emits today.
    *
    * This calls the `generate` library entry point directly. It used to be a
    * shell pipeline in CI that ran `runMain parseP4info`, sed'd the types out of
    * sbt's log and diffed them. That went green locally and failed in real CI:
    * sbt colourises its output there, so `[success]` is actually
    * `[<ESC>[32msuccess<ESC>[0m]` and the sed that was meant to cut sbt's trailer
    * never matched — appending the success line and a screen-clear escape to the
    * compared text. Calling `generate` directly removes the log parsing entirely.
    *
    * The p4info comes off the test classpath; the expected output is read from
    * the source tree, since sbt excludes *.scala from resources and it has to
    * stay a compiled source (that is what proves the emitted types compile).
    */
  test("typegen output matches the committed quackmpp_exchange.scala") {
    val p4info = scala.io.Source.fromResource("quackmpp_exchange.p4info.json").mkString

    // user.dir is the project base directory because build.sbt sets
    // `Test / fork := true` — sbt gives a forked test JVM
    // workingDirectory = baseDirectory. Unforked it would just inherit sbt's cwd.
    val fixture = java.io.File(
      System.getProperty("user.dir"), "src/test/scala/quackmpp_exchange.scala"
    )
    assert(fixture.isFile, s"fixture not found at ${fixture.getAbsolutePath}")
    val committed = scala.io.Source.fromFile(fixture).mkString

    generate(p4info, "quackmpp") match
      case Left(err) => fail(s"typegen failed: $err")
      case Right(out) =>
        assertEquals(
          out,
          committed,
          "typegen output drifted from src/test/scala/quackmpp_exchange.scala — regenerate it"
        )
  }

  /** Pins a KNOWN LIMITATION of the v1.5.0 proto refresh, so it is a documented
    * behaviour rather than a surprise in the field.
    *
    * P4Runtime v1.4.0 replaced `ActionProfile.selector_size_semantics` — an enum
    * at field 6 — with a `oneof` whose `sum_of_weights` arm reuses field 6 as a
    * *message*. That flips the wire type varint -> length-delimited, so it is the
    * one genuinely wire-incompatible change in the refresh (unlike `Replica`,
    * where field 1 keeps its number and type).
    *
    * Consequence: a p4info emitted by a p4c predating v1.4.0 cannot be parsed
    * against our protos IF it uses action-profile selectors. It only bites there
    * — none of the other fixtures declare an actionProfile at all, which is why
    * they are unaffected. See UPGRADE.md §5.
    */
  test("a pre-v1.4.0 p4info using action-profile selectors is rejected, with a clear error") {
    val legacy = scala.io.Source.fromResource("legacy_actionprofile.p4info.json").mkString
    generate(legacy, "legacy") match
      case Right(_) =>
        fail("expected the pre-v1.4.0 selector_size_semantics shape to be rejected")
      case Left(err) =>
        assert(
          err.contains("selectorSizeSemantics"),
          s"expected the error to name the incompatible field, got: $err"
        )
  }

  test("generate reports malformed p4info JSON as a Left rather than throwing") {
    generate("{ not json", "quackmpp") match
      case Left(err) => assert(err.contains("could not parse p4info JSON"), err)
      case Right(_)  => fail("malformed JSON must not produce output")
  }

  test("a table entry matching on the real 'bucket' field compiles") {
    val entry = TableEntry[TableMatchFields, TableAction, ActionParams](
      "QuackMPP.exchange",
      ("meta.quack.bucket", Exact(bytes(0, 7))),
      "QuackMPP.set_worker",
      (("worker_id", bytes(0, 0, 0, 3)), ("port", bytes(2))),
      1
    )
    assertEquals(entry.table, "QuackMPP.exchange")
    assertEquals(entry.priority, 1)
  }

  // Control. Without this, the two negative tests below would still pass if
  // `compileErrors` rejected every snippet for some unrelated reason (a bad
  // import, a changed TableEntry signature), and they would silently stop
  // testing the guarantee they exist for.
  test("control: the correct snippet reports no compile errors") {
    val errors = compileErrors(
      """TableEntry[TableMatchFields, TableAction, ActionParams](
           "QuackMPP.exchange",
           ("meta.quack.bucket", Exact(bytes(0, 7))),
           "QuackMPP.set_worker",
           (("worker_id", bytes(0, 0, 0, 3)), ("port", bytes(2))),
           1
         )"""
    )
    assertEquals(errors, "", "the valid snippet must compile cleanly")
  }

  test("a renamed match field is rejected at compile time") {
    val errors = compileErrors(
      """TableEntry[TableMatchFields, TableAction, ActionParams](
           "QuackMPP.exchange",
           ("meta.quack.bucket_renamed", Exact(bytes(0, 7))),
           "QuackMPP.set_worker",
           (("worker_id", bytes(0, 0, 0, 3)), ("port", bytes(2))),
           1
         )"""
    )
    assert(errors.nonEmpty, "renaming the 'bucket' match field must not compile")
  }

  test("an action not declared for the table is rejected at compile time") {
    val errors = compileErrors(
      """TableEntry[TableMatchFields, TableAction, ActionParams](
           "QuackMPP.exchange",
           ("meta.quack.bucket", Exact(bytes(0, 7))),
           "QuackMPP.nonexistent_action",
           (("worker_id", bytes(0, 0, 0, 3)), ("port", bytes(2))),
           1
         )"""
    )
    assert(errors.nonEmpty, "an undeclared action must not compile")
  }
}
