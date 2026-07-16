package p4rtype.consumertest

import io.grpc.ManagedChannelBuilder
import p4.v1.p4runtime.P4RuntimeGrpc
import p4.v1.p4runtime.{CapabilitiesRequest, GetForwardingPipelineConfigRequest}

/** Integration test against a real bmv2 `simple_switch_grpc`.
  *
  * Everything else in this repo is compile-and-typecheck. This is the only thing
  * that puts the upgraded stack — grpc-netty 1.82.2 and the P4Runtime v1.5.0
  * protos — on an actual wire against an actual switch. UPGRADE.md §5 reasons
  * about bmv2 compatibility from proto diffs and PI's submodule pin; this
  * *measures* it.
  *
  * Skipped unless P4RT_BMV2 is set (`host:port`), so the normal suite stays fast
  * and container-free. Easiest way to run it:
  *
  * {{{
  * container/p4rt.sh test        # starts bmv2, waits for it, runs this suite
  * }}}
  *
  * By hand, if you must — and mind the `--`:
  *
  * {{{
  * container run -d --name bmv2 --platform linux/amd64 -p 9559:9559 \
  *   docker.io/p4lang/behavioral-model:latest \
  *   simple_switch_grpc --no-p4 -- --grpc-server-addr 0.0.0.0:9559
  * P4RT_BMV2=localhost:9559 sbt "testOnly *Bmv2WireSuite"
  * }}}
  *
  * The `--` is load-bearing: `--grpc-server-addr` is a target-specific option,
  * and without the separator bmv2 prints usage and exits — while the published
  * port still accepts connections, so it looks up and you get an unexplained
  * UNAVAILABLE here. `localhost`, too, not the IP `container ls` prints: that
  * address is not routable from the macOS host. See ARCHITECTURE.md §5.
  */
class Bmv2WireSuite extends munit.FunSuite {

  private val target = Option(System.getenv("P4RT_BMV2"))

  private def withStub[A](f: P4RuntimeGrpc.P4RuntimeBlockingStub => A): A =
    val Array(host, port) = target.get.split(":")
    val channel = ManagedChannelBuilder.forAddress(host, port.toInt).usePlaintext().build()
    try f(P4RuntimeGrpc.blockingStub(channel))
    finally channel.shutdownNow()

  override def munitTests(): Seq[Test] =
    if target.isDefined then super.munitTests()
    else
      // Announce rather than silently pass: a skipped integration test that
      // looks identical to a passing one is how "we tested it" becomes false.
      println("[info] Bmv2WireSuite skipped: set P4RT_BMV2=host:port to run it")
      Nil

  test("bmv2 answers Capabilities over the upgraded gRPC/proto stack") {
    val resp = withStub(_.capabilities(CapabilitiesRequest()))
    assert(
      resp.p4RuntimeApiVersion.nonEmpty,
      "bmv2 returned an empty p4runtime_api_version"
    )
    // Printed on purpose: this is the ground truth behind UPGRADE.md §5. bmv2
    // reports 1.3.0, which is *why* the v1.4.0 ActionProfile
    // selector_size_semantics change (enum field 6 -> message field 6) is a real
    // incompatibility for selector pipelines rather than a theoretical one.
    println(s"[info] bmv2 reports p4runtime_api_version = ${resp.p4RuntimeApiVersion}")
    assertEquals(
      resp.p4RuntimeApiVersion,
      "1.3.0",
      "bmv2's P4Runtime version changed — re-check UPGRADE.md §5's compatibility analysis"
    )
  }

  test("bmv2 rejects GetForwardingPipelineConfig with no pipeline, over v1.5.0 protos") {
    // Started with `--no-p4`, so bmv2 answers FAILED_PRECONDITION. That *is* the
    // round-trip: the request was encoded with our v1.5.0 protos, understood by a
    // 1.3.0 switch, and a structured gRPC status came back. A generic transport
    // failure (UNAVAILABLE) would look nothing like this.
    val err = intercept[io.grpc.StatusRuntimeException] {
      withStub(_.getForwardingPipelineConfig(GetForwardingPipelineConfigRequest(deviceId = 0)))
    }
    assertEquals(err.getStatus.getCode, io.grpc.Status.Code.FAILED_PRECONDITION)
  }
}
