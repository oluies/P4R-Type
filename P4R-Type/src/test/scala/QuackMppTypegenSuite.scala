// This suite deliberately lives in a NAMED package, not the empty package.
// `generate` is meant to be called by a downstream build (QuackMPP's Mill build
// compiles into `build`/`millbuild`), and the empty package is not importable
// from a named one. A test in the empty package would resolve `generate` even if
// no real consumer could, so it would prove nothing about the thing that matters.
package p4rtype.consumertest

import p4rtype.{Exact, LPM, Range, Ternary, Optional, TableEntry, bytes}
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

  /** P4R-Type emits canonical binary strings, which is what gives P4Runtime
    * read-write symmetry: a server replies with the canonical form, so a client
    * that writes a longer encoding reads back something different from what it
    * wrote (was UPGRADE.md §8.12).
    *
    * These check the *write path* rather than what a switch returns, which is
    * the only place the property is visible: a switch canonicalises regardless,
    * so a read-back value looks the same either way. That also means they run in
    * CI with no bmv2.
    */
  /** Construction is where canonicalisation happens, so this is the assertion
    * that can actually fail. `matchFieldToProto` no longer calls `canonical` —
    * it cannot receive a non-canonical value — so a wire test alone would not
    * distinguish "the write path canonicalises" from "the value already was".
    */
  test("match-field values are canonical at construction") {
    assertEquals(
      Exact(bytes(0, 7)).v, bytes(7),
      "bit<16> 7 must be the shortest string, bytes(7); the spec's own table " +
      "marks the 2-byte form as read-write symmetry: no"
    )

    // The equality this buys is the whole point: it is what lets a controller
    // diff a read-back entry against the entry it intended.
    assertEquals(Exact(bytes(0, 7)), Exact(bytes(7)))
    assertEquals(LPM(bytes(0, 10, 0, 1), 24), LPM(bytes(10, 0, 1), 24))
    assertEquals(Ternary(bytes(0, 7), bytes(0, 0xff.toByte)), Ternary(bytes(7), bytes(0xff.toByte)))
    assertEquals(Range(bytes(0, 1), bytes(0, 9)), Range(bytes(1), bytes(9)))
    assertEquals(Optional(bytes(0, 0, 5)), Optional(bytes(5)))
  }

  test("matchFieldToProto puts the canonical value on the wire") {
    val fm = p4rtype.matchFieldToProto(1, Exact(bytes(0, 7)))
    assertEquals(fm.fieldMatchType.exact.get.value, bytes(7))
  }

  test("canonical leaves an already-canonical value alone") {
    // The examples rely on this: bytes(10,0,1,1) is an IPv4 address whose
    // interior zero must survive — only *leading* zeros go.
    assertEquals(p4rtype.canonical(bytes(10, 0, 1, 1)), bytes(10, 0, 1, 1))
    assertEquals(p4rtype.canonical(bytes(0x30, 0x64)), bytes(0x30, 0x64))
  }

  test("canonical encodes zero as one byte, not the empty string") {
    // The spec defines zero as needing one bit, hence one byte, and says a
    // zero-length string is always rejected by the server. Dropping every zero
    // byte would produce exactly that rejected encoding.
    assertEquals(p4rtype.canonical(bytes(0, 0, 0, 0)), bytes(0))
    assertEquals(p4rtype.canonical(bytes(0)), bytes(0))

    // The empty string is reachable: `bytes` is varargs, so `Exact(bytes())`
    // produces it, as does any default-valued proto `bytes` field coming back
    // through fromProto into a re-written entry. Returning it unchanged would
    // emit exactly the encoding the spec says a server always rejects — which
    // the scaladoc promises never happens.
    assertEquals(p4rtype.canonical(bytes()), bytes(0))
  }

  /** Pins what actually goes on the wire, through the whole write path.
    *
    * The per-function tests above cover `matchFieldToProto`, but canonicalising
    * is two halves and the other one lives in *generated* code: `typegen` emits
    * `Param(paramId = 1, value = p4rtype.canonical(p0))`. Testing only
    * `matchFieldToProto` would let someone fix or break the param half with no
    * signal — and `router.scala` feeds one computed value into both an `Exact`
    * and an action param, so the halves are not hypothetically independent.
    *
    * `Chan.toProto` reads neither `socket` nor `channel`, so it can be driven
    * with nulls: this is the real conversion, no bmv2 needed.
    */
  test("the whole write path canonicalises: match fields AND action params") {
    val chan = quackmpp.Chan(0, null, null)

    val proto = chan.toProto(TableEntry[TableMatchFields, TableAction, ActionParams](
      "QuackMPP.exchange",
      ("meta.quack.bucket", Exact(bytes(0, 7))),              // 2 bytes in
      "QuackMPP.set_worker",
      (("worker_id", bytes(0, 0, 0, 3)), ("port", bytes(0, 2))), // 4 and 2 bytes in
      0
    ))

    assertEquals(
      proto.`match`.head.fieldMatchType.exact.get.value, bytes(7),
      "match field must go on the wire canonical"
    )

    val params = proto.action.get.`type`.action.get.params
    assertEquals(
      params.map(_.value), Seq(bytes(3), bytes(2)),
      "action params must go on the wire canonical too — this is the half that " +
      "lives in typegen's emitted code, not in matchFieldToProto"
    )
  }

  // Every match type reaches the wire canonical, and nothing but the leading
  // zeros is disturbed — prefixLen, masks and interior zeros all survive.
  test("every match type reaches the wire canonical, with nothing else changed") {
    val lpm = p4rtype.matchFieldToProto(1, LPM(bytes(0, 10, 0, 1), 24))
    assertEquals(lpm.fieldMatchType.lpm.get.value, bytes(10, 0, 1))
    assertEquals(lpm.fieldMatchType.lpm.get.prefixLen, 24, "prefixLen is untouched")

    val tern = p4rtype.matchFieldToProto(1, Ternary(bytes(0, 7), bytes(0, 0xff.toByte)))
    assertEquals(tern.fieldMatchType.ternary.get.value, bytes(7))
    assertEquals(tern.fieldMatchType.ternary.get.mask, bytes(0xff.toByte))

    val rng = p4rtype.matchFieldToProto(1, Range(bytes(0, 1), bytes(0, 9)))
    assertEquals(rng.fieldMatchType.range.get.low, bytes(1))
    assertEquals(rng.fieldMatchType.range.get.high, bytes(9))

    val opt = p4rtype.matchFieldToProto(1, Optional(bytes(0, 0, 5)))
    assertEquals(opt.fieldMatchType.optional.get.value, bytes(5))
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
