package p4rtype.consumertest

import p4rtype.{Exact, TableEntry, bytes, insert, read, setForwardingPipelineConfig, getForwardingPipelineConfig}
import quackmpp.{TableMatchFields, TableAction, ActionParams}
import com.google.protobuf.ByteString
import p4.config.v1.p4info.P4Info

/** The full loop, against a real switch: push a pipeline, insert a table entry
  * keyed on `bucket`, read it back.
  *
  * This is what `setForwardingPipelineConfig` unlocked. `Bmv2WireSuite` could
  * only reach RPCs that need no pipeline (Capabilities), because P4R-Type had no
  * way to install one — so nothing here had ever proven that a `TableEntry`
  * built through the generated types actually lands in a switch's table.
  *
  * Needs a bmv2 AND the compiled dataplane, so it is skipped unless both
  * P4RT_BMV2 (`host:port`) and P4RT_BMV2_JSON (path to quackmpp.json) are set:
  *
  * {{{
  * container/p4rt.sh pipeline-test
  * }}}
  */
class Bmv2PipelineSuite extends munit.FunSuite {

  private val target     = Option(System.getenv("P4RT_BMV2"))
  private val deviceJson = Option(System.getenv("P4RT_BMV2_JSON"))

  override def munitTests(): Seq[Test] =
    if target.isDefined && deviceJson.isDefined then super.munitTests()
    else
      println("[info] Bmv2PipelineSuite skipped: set P4RT_BMV2=host:port and P4RT_BMV2_JSON=/path/to/quackmpp.json")
      Nil

  // munit runs tests in declaration order within a suite, but the pipeline has to
  // be installed before the table ops regardless — so do it in a fixture that
  // runs once for the suite rather than relying on that.
  private var chan: quackmpp.Chan = null

  override def beforeAll(): Unit =
    if target.isEmpty || deviceJson.isEmpty then return
    val Array(host, port) = target.get.split(":")
    chan = quackmpp.connect(0, host, port.toInt)

    val p4infoText = scala.io.Source.fromResource("quackmpp_exchange.p4info.json").mkString
    val p4info = typegen.generate(p4infoText, "unused") match
      case Left(err) => fail(s"fixture p4info did not parse: $err")
      case Right(_)  =>
        // generate() proves it parses; we need the message itself here.
        val b = com.google.protobuf.DynamicMessage.newBuilder(P4Info.javaDescriptor)
        com.google.protobuf.util.JsonFormat.parser().merge(p4infoText, b)
        P4Info.parseFrom(b.build().toByteArray)

    val deviceConfig = ByteString.copyFrom(java.nio.file.Files.readAllBytes(
      java.nio.file.Path.of(deviceJson.get)
    ))

    setForwardingPipelineConfig(chan, p4info, deviceConfig) match
      case Left(err) => fail(s"could not install the pipeline: $err")
      case Right(_)  => ()

  override def afterAll(): Unit =
    if chan != null then chan.disconnect()

  test("the pushed pipeline reads back") {
    getForwardingPipelineConfig(chan) match
      case Left(err) => fail(s"could not read the pipeline back: $err")
      case Right(cfg) =>
        val names = cfg.p4Info.toSeq.flatMap(_.tables).flatMap(_.preamble).map(_.name)
        assert(
          names.contains("QuackMPP.exchange"),
          s"switch reports tables $names, expected QuackMPP.exchange"
        )
    }

  test("a table entry keyed on bucket inserts and reads back") {
    val entry = TableEntry[TableMatchFields, TableAction, ActionParams](
      "QuackMPP.exchange",
      ("meta.quack.bucket", Exact(bytes(0, 7))),   // bucket = 7
      "QuackMPP.set_worker",
      (("worker_id", bytes(0, 0, 0, 3)), ("port", bytes(2))),
      0                                            // exact-match tables take no priority
    )

    assert(insert(chan, entry), "insert returned false")

    val found = read(chan, TableEntry[TableMatchFields, TableAction, ActionParams](
      "*", "*", "*", "*", 0
    ))
    assertEquals(found.size, 1, s"expected exactly the entry we inserted, got $found")

    // The round trip is the point: the switch's protobuf comes back through the
    // generated fromProto and lands on the singleton-typed field name again.
    val (field, m) = found.head.matches.asInstanceOf[(String, Exact)]
    assertEquals(field, "meta.quack.bucket")
    assertEquals(found.head.action, "QuackMPP.set_worker")

    // NOTE: we wrote bytes(0, 7) — two bytes for a bit<16> field — and the
    // switch returns ONE byte, 0x07. That is not bmv2 being lax; it is the
    // P4Runtime canonical binary string: "the shortest string that fits the
    // encoded integer value" (spec §"Binary Strings"). The switch canonicalises,
    // P4R-Type does not.
    //
    // The consequence is that P4R-Type breaks the read-write symmetry the spec
    // asks for, whose own pseudocode is precisely this test:
    //
    //     status         = server.write(intended_value, p4_entity)
    //     observed_value = server.read(p4_entity)
    //     assert(intended_value == observed_value)     // fails here
    //
    // So `assertEquals(m.v, bytes(0, 7))` fails against a real switch. Asserting
    // the canonical value instead documents the actual behaviour rather than
    // hiding it — see UPGRADE.md §8.12 for the fix (canonicalise on write).
    assertEquals(m.v, bytes(7), "expected the switch's canonical 1-byte form")
    assertNotEquals(
      m.v, bytes(0, 7),
      "if this now matches, P4R-Type canonicalises on write and §8.12 is fixed — update this test"
    )
  }
}
