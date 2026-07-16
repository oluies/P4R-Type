import p4rtype.{Exact, TableEntry, bytes}
import quackmpp.{TableMatchFields, TableAction, ActionParams}

/** Pins the guarantee QuackMPP spec 003 depends on: the Scala 3 types generated
  * by `typegen` from a p4c/v1model p4info make a renamed or removed P4 match
  * field a *compile* error in the controller, not a runtime failure.
  *
  * The types under test are generated from
  * `src/main/scala/examples/quackmpp_exchange.p4info.json`, which declares an
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
    * sbt's log and diffed them — which was fragile in two ways worth remembering:
    * a genuine failure produced no output and got misreported as "drift", and the
    * JVM's sun.misc.Unsafe warnings on stderr could contaminate the comparison.
    * A library entry point makes all of that unnecessary.
    */
  test("typegen output matches the committed quackmpp_exchange.scala") {
    val p4info = scala.io.Source.fromResource("quackmpp_exchange.p4info.json").mkString
    val committed =
      scala.io.Source.fromFile(
        System.getProperty("user.dir") + "/src/test/scala/quackmpp_exchange.scala"
      ).mkString

    generate(p4info, "quackmpp") match
      case Left(err) => fail(s"typegen failed: $err")
      case Right(out) =>
        assertEquals(
          out,
          committed,
          "typegen output drifted from src/test/scala/quackmpp_exchange.scala — regenerate it"
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
