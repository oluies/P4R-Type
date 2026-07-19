package p4rtype.consumertest

import p4rtype.{Exact, LPM, Range, Ternary, Optional, TableEntry, bytes}
import matchkinds.{TableMatchFields, TableAction, ActionParams}

/** Covers the match kinds and the table shape that no other fixture reaches.
  *
  * Every p4info committed before `matchkinds.p4info.json` used only EXACT and
  * LPM, and every table in them had at most two match fields. That left two
  * things untested:
  *
  *  - `typegen`'s TERNARY / RANGE / OPTIONAL branches, in both the type and the
  *    `fromProto` emission. They produce Scala *source*, so a typo makes
  *    generated code that will not compile and nothing would have caught it.
  *  - Tables with three or more match fields. These were **broken**: the type
  *    was emitted as a left-nested pile of pairs, `((((f1, f2), f3), f4), f5)`,
  *    while `toProto` destructured a flat 5-tuple and `fromProto` built one.
  *    Any entry for such a table died with `scala.MatchError` inside `toProto`.
  *    At n <= 2 the two shapes coincide, which is why it survived: `((A), (B))`
  *    is `(A, B)`, parentheses around a single type not being a tuple.
  *
  * That this file compiles at all is half the test — it is generated code, and
  * the emission paths above run to produce it.
  */
class MatchKindsSuite extends munit.FunSuite {

  // Bit widths from the p4info, for reference when reading the values below:
  //   srcAddr bit<32>, totalLen bit<16>, protocol bit<8>, dstAddr bit<32>,
  //   bucket bit<16>, and forward(port bit<9>, dstAddr bit<48>).
  private def entry = TableEntry[TableMatchFields, TableAction, ActionParams](
    "MatchKinds.acl",
    (
      Some(("hdr.ipv4.srcAddr",  Ternary(bytes(10, 0, 0, 1), bytes(0xff.toByte)))),
      Some(("hdr.ipv4.totalLen", Range(bytes(0, 64), bytes(1, 44)))),
      Some(("hdr.ipv4.protocol", Optional(bytes(6)))),
      Some(("hdr.ipv4.dstAddr",  LPM(bytes(10, 0, 1, 1), 24))),
      ("meta.bucket", Exact(bytes(0, 7)))
    ),
    "MatchKinds.forward",
    (("port", bytes(0, 2)), ("dstAddr", bytes(1, 2, 3, 4, 5, 6))),
    // Nonzero, and required to be: P4Runtime fixes priority to 0 only for
    // tables whose fields are all exact. This is the other side of the rule
    // quackmpp.p4's exchange table exercises (ARCHITECTURE.md §7 gap 6).
    10
  )

  test("the committed matchkinds types are what typegen emits today") {
    val p4info = scala.io.Source.fromResource("matchkinds.p4info.json").mkString
    val fixture = java.io.File(
      System.getProperty("user.dir"), "src/test/scala/matchkinds.scala"
    )
    assert(fixture.isFile, s"fixture not found at ${fixture.getAbsolutePath}")
    val committed = scala.io.Source.fromFile(fixture).mkString

    typegen.generate(p4info, "matchkinds") match
      case Left(err)  => fail(s"typegen failed: $err")
      case Right(out) =>
        assertEquals(
          out, committed,
          "typegen output drifted from src/test/scala/matchkinds.scala — " +
          "regenerate with container/p4rt.sh gen-types"
        )
  }

  /** The regression test for the n >= 3 MatchError. Before the tuple type was
    * flattened this threw rather than returning.
    */
  test("a five-field table converts to protobuf instead of throwing") {
    val chan = matchkinds.Chan(0, null, null)
    val proto = chan.toProto(entry)

    assertEquals(
      proto.`match`.size, 5,
      "all five match fields must reach the wire; a short count means one of " +
      "the Option arms silently produced None"
    )
    assertEquals(proto.`match`.map(_.fieldId), Seq(1, 2, 3, 4, 5), "field ids, in p4info order")
  }

  test("each match kind lands in its own protobuf arm, canonical") {
    val chan = matchkinds.Chan(0, null, null)
    val fms = chan.toProto(entry).`match`.map(fm => fm.fieldId -> fm.fieldMatchType).toMap

    val tern = fms(1).ternary.get
    assertEquals(tern.value, bytes(10, 0, 0, 1), "interior zeros survive; only leading ones go")
    assertEquals(tern.mask, bytes(0xff.toByte))

    val rng = fms(2).range.get
    assertEquals(rng.low, bytes(64), "0x00,0x40 canonicalises to 0x40")
    assertEquals(rng.high, bytes(1, 44), "0x01,0x2c is already canonical")

    assertEquals(fms(3).optional.get.value, bytes(6))

    val lpm = fms(4).lpm.get
    assertEquals(lpm.value, bytes(10, 0, 1, 1))
    assertEquals(lpm.prefixLen, 24, "prefixLen is not a binary string and is untouched")

    assertEquals(fms(5).exact.get.value, bytes(7), "0x00,0x07 canonicalises to 0x07")
  }

  test("action params on a multi-field table are canonical too") {
    val chan = matchkinds.Chan(0, null, null)
    val params = chan.toProto(entry).action.get.`type`.action.get.params
    assertEquals(
      params.map(_.value), Seq(bytes(2), bytes(1, 2, 3, 4, 5, 6)),
      "port bytes(0, 2) canonicalises to bytes(2); the bit<48> address is " +
      "already canonical and must not be shortened"
    )
  }

  test("a match field that does not exist on this table is rejected at compile time") {
    val errors = compileErrors(
      """TableEntry[TableMatchFields, TableAction, ActionParams](
           "MatchKinds.acl",
           (
             Some(("hdr.ipv4.srcAddr_renamed", Ternary(bytes(10, 0, 0, 1), bytes(-1)))),
             Some(("hdr.ipv4.totalLen", Range(bytes(0, 64), bytes(1, 44)))),
             Some(("hdr.ipv4.protocol", Optional(bytes(6)))),
             Some(("hdr.ipv4.dstAddr",  LPM(bytes(10, 0, 1, 1), 24))),
             ("meta.bucket", Exact(bytes(0, 7)))
           ),
           "MatchKinds.forward",
           (("port", bytes(0, 2)), ("dstAddr", bytes(1, 2, 3, 4, 5, 6))),
           10
         )"""
    )
    assert(errors.nonEmpty, "renaming a ternary match field must not compile")
  }

  // Control, as in QuackMppTypegenSuite: without it the negative test above
  // would pass even if compileErrors rejected every snippet for some unrelated
  // reason, and would silently stop testing anything.
  test("control: the correct five-field snippet reports no compile errors") {
    val errors = compileErrors(
      """TableEntry[TableMatchFields, TableAction, ActionParams](
           "MatchKinds.acl",
           (
             Some(("hdr.ipv4.srcAddr", Ternary(bytes(10, 0, 0, 1), bytes(-1)))),
             Some(("hdr.ipv4.totalLen", Range(bytes(0, 64), bytes(1, 44)))),
             Some(("hdr.ipv4.protocol", Optional(bytes(6)))),
             Some(("hdr.ipv4.dstAddr",  LPM(bytes(10, 0, 1, 1), 24))),
             ("meta.bucket", Exact(bytes(0, 7)))
           ),
           "MatchKinds.forward",
           (("port", bytes(0, 2)), ("dstAddr", bytes(1, 2, 3, 4, 5, 6))),
           10
         )"""
    )
    assertEquals(errors, "", "the valid five-field snippet must compile cleanly")
  }
}
