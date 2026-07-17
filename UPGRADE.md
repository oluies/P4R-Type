# P4R-Type upgrade record

This fork of [JensKanstrupLarsen/P4R-Type](https://github.com/JensKanstrupLarsen/P4R-Type)
(the OOPSLA artifact) is being brought up to date to serve as the control-plane
binding library for **QuackMPP**.

Verified on **JDK 25.0.3 / sbt 2.0.3 / Scala 3.8.4**, cold action cache:
`compile` and `testFull` both green, 12/12 tests passing.

---

## 1. Baseline (as inherited)

The artifact did not build at all on a current machine. sbt 1.7.1 runs its build
parser on Scala 2.12, which cannot read Java 26 class files:

```
[error] java.lang.NoClassDefFoundError: Could not initialize class sbt.internal.parser.SbtParser$
```

So the upgrade was not optional — there was no working baseline to regress against.

| Component | Before | After |
| --- | --- | --- |
| sbt | 1.7.1 | **2.0.3** |
| Scala | 3.1.3 | **3.8.4** (Next track) |
| JDK | unpinned (broken on 25/26) | **25 LTS** (CI-pinned) |
| sbt-protoc | 1.0.3 *and* 1.0.2 (declared twice) | **1.1.0-RC2** |
| ScalaPB compilerplugin | 0.11.11 | **1.0.0-alpha.6** |
| scalapb-runtime / -grpc | 0.11.11 | **1.0.0-alpha.6** |
| scalapb-json4s | 0.12.0 | **removed** — replaced by protobuf-java-util (§3) |
| zio-grpc-codegen | 0.6.0-test4 | **removed** (see §4) |
| zio | 2.0.0 | **removed** (see §4) |
| zio-streams | (transitive) | **removed** (see §4) |
| grpc-netty | 1.41.0 (and 1.41.0 again) | **1.82.2** |
| protobuf-java | (via ScalaPB 0.11.11) | **4.35.0** (via ScalaPB alpha.6) |
| protobuf-java-util | — | **4.35.1** (new: p4info JSON parsing) |
| munit | 0.7.29 | **1.3.4** |
| sbt-bloop (`project/metals.sbt`) | 1.5.8, committed | **removed + gitignored** |
| P4Runtime protos | ~v1.3-era | **v1.5.0** (see §5) |

`build.sbt` also declared `PB.targets`, `grpc-netty`, and `scalapb-runtime-grpc`
twice each; the file has been deduplicated.

## 2. The codegen was silently doing nothing

The single most important finding.

`sbt-protoc` defaults `PB.protoSources` to `src/main/protobuf`. This repo keeps
its `.proto` files in `src/protobuf`. That directory does not exist, so
**`PB.targets` generated zero files** and the 120 `.scala` files committed under
`src/main/scala/protobuf/` were what actually compiled. ScalaPB was, in effect,
vendored-by-copy with the plugin along for the ride.

Fixed by pointing `protoSources` at the real location:

```scala
Compile / PB.protoSources := Seq(baseDirectory.value / "src" / "protobuf")
```

and deleting the committed generated tree. To confirm nothing had been
hand-edited, the regenerated file set was diffed against the committed one:
**119 generated vs 120 committed, identical names, the only difference being
`ZioP4Runtime.scala`** — the zio-grpc stub that is no longer generated (§4).
Nothing was lost. (The build now generates 120: `Any.scala` / `AnyProto.scala`
dropped out — see below — and the v1.5.0 refresh added five, see §5.)

Consequence: ScalaPB/gRPC/proto versions are now *real* inputs. Bumping them
actually changes the generated API, and `.proto` edits now take effect.

That cuts both ways. `src/protobuf/` vendored `google/protobuf/any.proto`, which
— once codegen was live — generated `com.google.protobuf.any.Any` into
`src_managed`, a class **`scalapb-runtime` already ships**, from a proto older
than the protobuf-java 4.35.0 the runtime pulls. The locally compiled copy
shadowed the library's. The vendored copy has been deleted; protoc resolves the
import from its built-in well-known-types include path, and `Any` now comes from
`scalapb-runtime`. `google/rpc/status.proto` is *not* shipped by scalapb-runtime
and correctly stays vendored.

## 3. Pre-release dependencies (the cost of sbt 2)

sbt 2 runs its build on Scala 3, so build-side artifacts must be `_3`. That
forces a chain of pre-release versions:

- `sbt-protoc` publishes an sbt 2 build only as **`sbt-protoc_sbt2_3` 1.1.0-RC1/RC2**.
  There is no stable sbt 2 release.
- ScalaPB **0.11.x cannot be used under sbt 2**: `compilerplugin_3` 0.11.x depends on
  `protoc-gen_2.13` → `protoc-bridge_2.13`, which collides with sbt-protoc's
  `protoc-bridge_3`:
  ```
  [error] Conflicting cross-version suffixes in: com.thesamet.scalapb:protoc-bridge
  ```
  Only **1.0.0-alpha.5+** switched to `protoc-gen_3` / `protoc-bridge_3` 0.9.10.
- ~~`scalapb-json4s`~~ — **removed**, see below.

### scalapb-json4s is gone, and with it the eviction override

`scalapb-json4s` was the worst of the four: the 1.0 line has exactly one release
(**1.0.0-alpha.1**), which pins `scalapb-runtime` 1.0.0-alpha.1 against the
alpha.6 the rest of the build needs. That required a `libraryDependencySchemes`
override just to resolve, and amounted to a cross-alpha binary-compatibility
gamble on every json4s call.

It was used in exactly one place — parsing p4info JSON in `generate`. That is now
done with **protobuf-java's `JsonFormat`** (the reference implementation of the
protobuf JSON mapping p4c emits) through a `DynamicMessage` built from ScalaPB's
own `javaDescriptor`, then re-parsed into the Scala message:

```scala
val builder = DynamicMessage.newBuilder(P4Info.javaDescriptor)
JsonFormat.parser().merge(p4infoJson, builder)
P4Info.parseFrom(builder.build().toByteArray)
```

`protobuf-java-util` is a plain Java artifact — no Scala cross-version, no alpha —
and 4.35.0 exactly matches the protobuf-java `scalapb-runtime` already pulls.

Verified equivalent, not assumed: typegen output was generated for **all six**
p4info fixtures under both parsers and compared. Byte-for-byte identical in every
case, including the ternary/action-profile-heavy `config2_nat`. The eviction
override is deleted and the build now resolves with **zero conflict warnings**.

### What pre-release surface remains

Two artifacts, both forced purely by sbt 2 needing `_3` build-side jars:

| artifact | version | why |
| --- | --- | --- |
| `sbt-protoc` | 1.1.0-RC2 | no stable sbt 2 release exists at all |
| ScalaPB `compilerplugin` / `scalapb-runtime` | 1.0.0-alpha.6 | 0.11.x pulls `protoc-bridge_2.13`, which collides with sbt-protoc's `_3` |

Re-checked on Maven at the time of writing: nothing newer exists on either
(`sbt-protoc_sbt2_3` has only RC1/RC2; `compilerplugin_3` tops out at
1.0.0-alpha.6). sbt is now 2.0.3 — released mid-upgrade, and landed via Scala
Steward's own gated PR, which is the setup working exactly as intended.

### Recommendation: stay on sbt 2.x

The fragility that justified the escape hatch is gone. What is left is two
pre-release *versions* rather than an unresolvable *conflict*: no override, no
eviction warning, no cross-alpha gamble — and 12/12 tests green on a cold JDK 25
build.

**sbt 1.12.13 remains a clean fallback** and is worth taking if the RC/alpha
versions are themselves a policy problem (e.g. for a published artifact others
depend on). Everything resolves stable there: sbt-protoc 1.0.8, compilerplugin
0.11.20, scalapb-runtime 0.11.20, all on `protoc-bridge_2.12` — and the
protobuf-java-util parser change above is orthogonal, so it carries over. The
library API is identical either way; only `project/` changes.

Scala Steward is configured to move the two pins forward as soon as
`sbt-protoc` 1.1.0 and ScalaPB 1.0.0 go final, which is the real exit.

## 4. zio-grpc was removed

`zio-grpc` has **no sbt 2 compatible codegen release**. The newest,
`zio-grpc-codegen` 0.6.3, pins `compilerplugin_3` 0.11.14 → `protoc-bridge_2.13`,
reproducing the §3 conflict with no version that escapes it.

This turned out to be free: the only references to zio-grpc anywhere in
non-generated source were **three unused imports** in `typegen.scala`
(`scalapb.zio_grpc.{ServerMain, ServiceList}`, `ZioP4Runtime.*`,
`ZioP4Runtime.P4RuntimeClient.ZService`). No symbol from them was referenced.
They were removed along with `ZioCodeGenerator` from `PB.targets`. (A stray
`org.checkerframework.checker.guieffect.qual.UI` import went with them.)

### ZIO itself is gone too

`zio` initially stayed, since `typegen.scala` used `ZIOAppDefault`, `ZLayer`,
`RIO`, `ZStream` and `ZSink`. On inspection none of it was doing any work: ZIO
appeared in exactly one file, and only as `RIO[Unit, String]` return types,
`ZIO.succeed`/`ZIO.fail`, a hand-rolled `mapM` traverse, and a `ZStream`/`ZSink`
pipeline to write a string to stdout. There is no concurrency, no async, no
resource management, no environment — `typegen` reads one file, builds a String,
and prints it. `RIO[Unit, X]` for a pure fallible computation *is* `Either`.

So it was replaced mechanically: `RIO[Unit, X]` → `Either[String, X]` (28),
`ZIO.fail(new Exception(m))` → `Left(m)` (20), `ZIO.succeed` → `Right` (31).
For-comprehensions work unchanged, since `Either` is a monad in Scala 3. `mapM`
became a fold over `Either` with the same fail-fast-in-order semantics, and the
`ZStream`/`ZSink`-to-stdout pipeline became `print`.

Verified: the emitted types are **byte-identical** (same md5 as the committed
fixture, checked both through the CLI and through `generate`). Error paths
improved as a side effect — bad arg count, unreadable file and malformed JSON now
exit 1 with a one-line message instead of a ZIO `FiberFailure` stack trace.

This drops `zio` + `zio-streams` entirely. P4R-Type's only remaining runtime
dependencies are ScalaPB, gRPC and protobuf-java — appropriate for a library
QuackMPP embeds, since it no longer forces a ZIO version on its consumers.

## 5. P4Runtime protos — refreshed to v1.5.0

Upstream `p4lang/p4runtime` is at **v1.5.0** (Feb 2026). `p4data.proto` and
`p4types.proto` turned out to be byte-identical to v1.5.0 already; only
`p4runtime.proto` (793 → 875 lines) and `p4info.proto` (390 → 479) were behind.
All four are now at v1.5.0.

**The upgrade required zero source changes.** `compile` + `testFull` pass, and the
`bucket` fixture still regenerates byte-identically. Generated files 117 → 120
(`PlatformProperties`, `TableActionCall`, `BackupReplica`).

What changed, checked field-by-field rather than assumed:

- **The service contract is unchanged.** Same six RPCs (`Write`, `Read`,
  `SetForwardingPipelineConfig`, `GetForwardingPipelineConfig`, `StreamChannel`,
  `Capabilities`) with identical signatures.
- **`p4info.proto` is additive except for one wire-incompatible change** —
  `ActionProfile.selector_size_semantics`. See "The one real incompatibility"
  below. Otherwise new: `PlatformProperties` + `PkgInfo.platform_properties`
  (v1.4.0), `TableActionCall`, `Table.initial_default_action`,
  `ActionProfile.weights_disallowed`.
- **`p4runtime.proto` is additive with one API-shape change**: `TableEntry.is_const`,
  `MeterConfig.eburst`, `Replica.backup_replicas`/`BackupReplica` (v1.5.0), and —
  the one to know about — `Replica.egress_port` is now inside a `oneof port_kind`
  alongside `bytes port = 3` (v1.4.0):

  ```protobuf
  message Replica {
    oneof port_kind {
      uint32 egress_port = 1 [deprecated=true];   // deprecated in v1.4.0
      bytes  port        = 3;                     // added in v1.4.0
    }
    uint32 instance = 2;
    repeated BackupReplica backup_replicas = 4;   // added in v1.5.0
  }
  ```

  Field 1 keeps its number and type, so this is **wire-compatible**, but it changes
  the *generated Scala API*: `Replica` now exposes `portKind: Replica.PortKind`
  (a sealed oneof) rather than a flat `egressPort`. Harmless here — P4R-Type does
  not reference `Replica`, multicast, or clone sessions anywhere — but see §8.9.

### The one real incompatibility: `ActionProfile.selector_size_semantics`

v1.4.0 replaced this enum field with a `oneof` that **reuses field number 6 with a
different wire type**:

```protobuf
// before (what bmv2's pinned P4Runtime still has)
enum SelectorSizeSemantics { SUM_OF_WEIGHTS = 0; SUM_OF_MEMBERS = 1; }
SelectorSizeSemantics selector_size_semantics = 6;   // varint

// v1.4.0+
oneof selector_size_semantics {
  SumOfWeights sum_of_weights = 6;                   // length-delimited!
  SumOfMembers sum_of_members = 7;
}
```

Field 6 goes varint → length-delimited and the `SelectorSizeSemantics` enum is
deleted. Unlike `Replica` (field 1 keeps its number *and* type), this one is
genuinely wire-incompatible, and it is **not** additive.

It is reproducible, not theoretical — a pre-v1.4.0 p4info fails outright:

```
Failure: could not parse p4info JSON: Cannot find field: selectorSizeSemantics
in message p4.config.v1.ActionProfile
```

`QuackMppTypegenSuite` pins this as a known limitation.

### So is v1.5.0 safe against bmv2?

**Safe for the pipelines in play, but with a real caveat — not unconditionally.**

**Measured, not inferred.** `Bmv2WireSuite` asks a real `simple_switch_grpc` over
the upgraded stack (grpc-netty 1.82.2 + v1.5.0 protos) and it answers:

```
[info] bmv2 reports p4runtime_api_version = 1.3.0
```

That both confirms the reasoning below *and* proves the wire works: a v1.5.0
client successfully drives a 1.3.0 switch. The static argument was:
bmv2's `simple_switch_grpc` gets P4Runtime from PI, which vendors
`p4lang/p4runtime` as a submodule pinned at `ec4eb5e` (2024-08-18) — *before* the
v1.4.0 release (2024-09-13). So bmv2 speaks ~v1.3.0-era P4Runtime and our client
protos are now ahead of the switch. The switch itself agrees.

1.3.0 is also what makes the `selector_size_semantics` break above **real rather
than theoretical**: bmv2 predates v1.4.0, so for a selector pipeline it would put
field 6 on the wire as a varint enum while we expect a length-delimited message.

That is fine **only where field 6 is unset**. Proto3 does not serialise an unset
oneof, so a p4info with no action-profile selectors is byte-compatible in both
directions. Every p4info in this repo — including `config1_lb`, the *load balancer*
— declares zero `actionProfiles`, so none of them touch it.

It is **not** fine if a pipeline uses action-profile selectors. Then:
- a p4info from a p4c predating v1.4.0 will not parse against our protos (above); and
- a P4Info we send via `SetForwardingPipelineConfig` puts field 6 on the wire as
  length-delimited, which bmv2's pre-v1.4.0 parser reads as a varint enum.

Pinning v1.4.1 does **not** help — the change landed *in* v1.4.0. Only staying
pre-v1.4.0 would, which then fails to parse p4info from any current p4c. So v1.5.0
is the right default; the constraint is that **p4c and bmv2 must agree with each
other**, and if selectors are in play they must both be ≥ v1.4.0.

> Correction, worth recording: an earlier revision of this section claimed
> `p4info.proto` was "purely additive — no field removed, none renumbered". That
> was wrong, and it was wrong because the script used to check it pushed a scope
> for `message`/`oneof` but not for `enum`, so the enum's closing brace popped
> `ActionProfile` early and mis-attributed the one field that mattered. A clean
> result from a broken verifier is worse than no check.

### Warning noise

A cold build (action cache cleared — see §9) emits **856 warnings, 799 of them
from ScalaPB-generated code**: ScalaPB 1.0.0-alpha.6's codegen still emits
`_` as a type wildcard (560) and `private[this]` (272), both of which Scala 3.8
warns about. The remaining 57 are in `src/main/scala/examples` (32) and
`src/main/scala/api` (25). All benign; none are errors. If the noise becomes a
problem, extend the existing `-Wconf` rule to silence `src_managed` rather than
editing generated output.

## 6. typegen verified on a p4c v1model p4info

The fixture `src/test/resources/quackmpp_exchange.p4info.json` is emitted by
**real p4c 1.2.5.15** (the `p4lang/p4c` container) from
`examples/src/main/p4/quackmpp.p4`, a P4-16/v1model program declaring table
`QuackMPP.exchange` with an exact match on `meta.quack.bucket` and actions
`set_worker(worker_id, port)`, `drop`, `NoAction`. A CI job recompiles it and
fails if the committed fixture drifts.

It was originally hand-written "in the shape p4c emits", which was close but not
right: real p4c also emits `initialDefaultAction` (the v1.5.0
`Table.initial_default_action`), which no amount of care would have guessed. That
is the argument for generating fixtures rather than imagining them.

`typegen` emits (committed as `src/test/scala/quackmpp_exchange.scala`, and it
compiles):

```scala
type TableMatchFields[TN] =
  TN match
    case "QuackMPP.exchange" => ("meta.quack.bucket", Exact) | "*"
    case "*" => "*"

type ActionParams[AN] =
  AN match
    case "QuackMPP.set_worker" => (("worker_id", ByteString), ("port", ByteString))
    ...
```

The match-field name is a **singleton string type**, which is exactly the
guarantee QuackMPP spec 003 needs. `src/test/scala/QuackMppTypegenSuite.scala`
pins it with munit's `compileErrors`:

- a `TableEntry` on `"meta.quack.bucket"` compiles;
- renaming it to `"meta.quack.bucket_renamed"` **fails to compile**;
- an action not declared for the table **fails to compile**.

### Note on exact vs. non-exact matches

`typegen` emits `("name", Exact)` for `EXACT` but `Option[("name", LPM)]` for
LPM/ternary/range/optional — because P4Runtime lets those be omitted (don't-care)
and exact fields be mandatory. So a controller writes:

```scala
TableEntry(..., ("meta.quack.bucket", Exact(bytes(0,7))), ...)   // exact: bare tuple
TableEntry(..., Some("hdr.ipv4.dstAddr", LPM(bytes(10,0,1,1), 32)), ...)  // lpm: Option
```

This trips people up: the existing `p4rtypetest.scala` example uses `Some(...)`
because its table is LPM-matched, not because that is the general form.

## 7. Build-tool decision: **keep sbt, consume as an external artifact**

**Recommendation: keep P4R-Type on sbt; do not port to Mill.**

Rationale:

1. **The ScalaPB/sbt-protoc chain is the entire build.** The value here is the
   protobuf codegen plus `typegen`. That is exactly the part that would have to be
   rebuilt on Mill's `ScalaPBModule`, and — per §3 — the sbt 2 side of that
   ecosystem is already RC/alpha. Porting means fighting two moving targets at once.
2. **The coupling to QuackMPP is a jar, not a build.** QuackMPP needs generated
   types and the `p4rtype` API. A published artifact fully expresses that.
3. **It is an upstream fork.** Staying on sbt keeps merges from
   `JensKanstrupLarsen/P4R-Type` tractable. A Mill port makes every upstream
   build change a manual reconciliation.
4. The counter-argument — "QuackMPP is Mill, so one build tool is simpler" — buys
   consistency at the cost of owning a codegen port. Not worth it for a dependency
   that changes rarely.

### How QuackMPP consumes it

`publishLocal` from `P4R-Type/`:

```bash
cd P4R-Type && sbt -batch publishLocal
```

Verified — this publishes to `~/.ivy2/local`:

```
io.github.oluies#p4rt-scala_3;0.1.0-SNAPSHOT
  -> ~/.ivy2/local/io.github.oluies/p4rt-scala_3/0.1.0-SNAPSHOT/jars/p4rt-scala_3.jar
```

`organization := "io.github.oluies"` is now set. Without it sbt derived the groupId
from the project name and published the meaningless `p4rt-scala %% p4rt-scala`.
(The groupId assumes eventual Maven Central publication under the `github.com/oluies`
namespace; change it before any real release if that is not the plan.)

The published jar was inspected rather than assumed — ScalaPB writes into
`sourceManaged`, so it is worth confirming generated classes are actually packaged:

| contents | count | |
| --- | --- | --- |
| `p4rtype/*` (the hand-written API) | 29 | wanted |
| `typegen/*` (CLI + `generate`) | 7 | wanted |
| `p4/v1/p4runtime/*` (generated) | 444 | wanted |
| `p4/config/v1/p4info/*` (generated) | 200 | wanted |
| default-package classes at the jar root | **0** | was 35 — fixed |
| `config1/`, `config2/`, `config1_lb/`, `config2_nat/`, `config2_new/` | **0** | was shipped — fixed |

The jar previously shipped every example: `bridge`, `firewall`, `forward_c1`,
`forward_c2`, `loadbalancer`, `p4rtypeTest` and `router` declare no package, so
they landed in the **default package** (35 `.class`/`.tasty` entries at the jar
root), and the `config*` packages are typegen output for a specific p4info — the
same category as the `quackmpp_exchange` fixture. The examples are now a separate,
never-published project (§8.8), and the counts above are verified, not assumed.

Mill 1.x (current release 1.1.7) renamed `ivyDeps`/`ivy"..."` to `mvnDeps`/`mvn"..."`,
so QuackMPP depends on it via:

```scala
def mvnDeps = Seq(mvn"io.github.oluies::p4rt-scala:0.1.0-SNAPSHOT")
```

On the resolver question: Mill resolves through coursier, and coursier's documented
defaults are "the Ivy2 local repository, `~/.ivy2/local`, [and] Maven Central" — so
**no custom `repositories` task should be needed**. Mill's own docs do not state this
explicitly, and it could not be tested here (neither Mill nor the coursier CLI is
installed on this machine), so confirm it on the QuackMPP side; if resolution fails,
adding `~/.ivy2/local` to Mill's `repositories` is the fix.

**Generated types are per-p4info and belong to the consumer.** `typegen` writes
Scala to stdout; QuackMPP should run it as a build step against its own p4info and
compile the output into its own module, rather than expecting P4R-Type to ship
QuackMPP's types. The `quackmpp_exchange.*` files here are a *fixture proving the
mechanism*, not the artifact QuackMPP should import — which is why they live under
`src/test/` (`src/test/scala/quackmpp_exchange.scala`,
`src/test/resources/quackmpp_exchange.p4info.json`). Putting the fixture in
`src/main/` shipped `quackmpp/Chan` inside the published library; the jar is now
verified free of `quackmpp/` entries.

## 8. Blockers / friction for QuackMPP spec 003

1. **Pre-release dependency chain (§3).** sbt 2 forces sbt-protoc RC2 + ScalaPB
   alpha.6 + a json4s eviction override. If QuackMPP wants a boring dependency,
   drop to sbt 1.12.13 — everything is stable there and the library API is
   unchanged either way.
2. ~~**`organization` is unset (§7).**~~ Fixed: `organization := "io.github.oluies"`,
   `publishLocal` verified to produce `io.github.oluies#p4rt-scala_3;0.1.0-SNAPSHOT`
   in `~/.ivy2/local` with all generated classes packaged (§7).
3. ~~**Protos are behind v1.5.0 (§5).**~~ Done — all four protos are at v1.5.0,
   with zero source changes and the `bucket` fixture unchanged (§5).
4. **There is no real test suite upstream.** The inherited suite was the sbt
   template's `assertEquals(42, 42)`. `testFull` being green proved nothing before
   `QuackMppTypegenSuite` was added. Treat upstream green as unverified.
5. ~~**`typegen` output is stdout-only.**~~ Fixed (§4): `typegen` now exposes a
   library entry point alongside the CLI —
   ```scala
   def generate(p4infoJson: String, packageName: String): Either[String, String]
   ```
   QuackMPP's Mill build can call this directly and write the result wherever it
   wants, instead of shelling out and scraping stdout. There is still no sbt/Mill
   *task* wrapping it; that belongs in the consumer's build.
6. **The examples require the mininet VM**, so they are compile-checked only.
   That gap is now partly closed without them: `Bmv2WireSuite` drives a real
   `simple_switch_grpc` container (see [ARCHITECTURE.md](ARCHITECTURE.md) §5), which
   is what turned §5's bmv2 analysis from reasoning into a measurement. A full
   table-entry round trip still needs blocker 9 below.
7. **`Chan`/`connect` hardcode an election id** of `Uint128(high=0, low=1)` and a
   single primary client. Multi-controller / role-based arbitration (which an MPP
   fabric may want) is not modelled.
8. ~~**The published jar ships the examples, 35 of them in the default package.**~~
    Fixed. `src/main/scala/examples/` is now the separate `examples` project
    (`examples/src/main/scala/`) with `publish / skip := true`, so the jar contains
    0 default-package entries and no `config*` packages — verified. The examples
    are still compiled (CI runs `examples/compile`) and still runnable via
    `sbt "examples/runMain <name>"`.

    Two notes for anyone touching this. `root` deliberately does **not**
    `.aggregate(examples)`: combined with `examples.dependsOn(root)` that hangs
    sbt 2 during project loading, which is why CI names `examples/compile`
    explicitly. And this was the same defect class as the `typegen` empty-package
    bug in §4 — moving the fixture out of `src/main/` (§7) fixed one instance and
    missed ten, because the check grepped the jar only for `quackmpp/`.
9. **If QuackMPP uses packet replication, read §5's `Replica` note first.** An MPP
   exchange fabric plausibly wants multicast/clone to fan packets across workers,
   and that is the one place the v1.5.0 refresh bites:
   - the generated API is `Replica(portKind = Replica.PortKind.EgressPort(n))`,
     not `Replica(egressPort = n)`;
   - and since bmv2 is pinned pre-v1.4.0 (§5), it will **not** understand the new
     `bytes port = 3` — against bmv2 you must use the *deprecated* `egress_port`
     arm of the oneof. Deprecated-but-correct here.

   P4R-Type itself models neither multicast nor clone sessions, so QuackMPP would
   be building on `p4.v1.p4runtime.*` directly rather than on the `p4rtype` API.
10. **Scala Steward's PRs are CI-gated only once `STEWARD_TOKEN` exists.** The
   workflow now reads `secrets.STEWARD_TOKEN` and falls back to `GITHUB_TOKEN`.
   PRs opened with `GITHUB_TOKEN` do not trigger workflow runs, so until the
   secret is added, bump PRs still merge without CI — which given §3 are the PRs
   that most need it. **Action for the repo owner:** create a fine-grained PAT
   scoped to this repo with Contents: read/write and Pull requests: read/write,
   and add it as `STEWARD_TOKEN`.

11. ~~**P4R-Type cannot install a forwarding pipeline.**~~ Fixed. `p4rtype`
   now exposes:
   ```scala
   def setForwardingPipelineConfig(c: Chan[_,_,_], p4info: P4Info,
                                   p4DeviceConfig: ByteString,
                                   action: SetForwardingPipelineConfigRequest.Action =
                                     VERIFY_AND_COMMIT): Either[String, Unit]
   def getForwardingPipelineConfig(c: Chan[_,_,_]): Either[String, ForwardingPipelineConfig]
   ```
   Both return the target's error rather than `write`'s bare `false`: a pipeline
   push is exactly where a switch tells you what it objected to, and a boolean
   throws that away. Verified against a real bmv2 by `Bmv2PipelineSuite`, which
   pushes the pipeline, inserts a `bucket` entry through the generated types, and
   reads it back — the round trip nothing could do before. Run it with
   `container/p4rt.sh pipeline-test`.

12. ~~**P4R-Type does not canonicalise binary strings, so it breaks P4Runtime
    read-write symmetry.**~~ Fixed. `p4rtype.canonical` strips leading zero bytes,
    and `matchFieldToProto` applies it to every match type while `typegen` now
    emits `p4rtype.canonical(...)` around action parameters.

    The defect, for the record: we wrote `Exact(bytes(0, 7))` for a `bit<16>`
    field and the switch returned `Exact(bytes(7))`. Not bmv2 being lax — a
    receiver imposes no maximum length, so the 2-byte form is *valid*. It simply
    is not canonical, and the spec's table of valid encodings marks `bit<16>` 99
    as `0x00 0x63` -> read-write symmetry "no", `0x63` -> "yes". Servers reply
    canonical, so writing a longer form meant reading back something else, and
    the spec's own pseudocode failed:
    ```python
    status         = server.write(intended_value, p4_entity)
    observed_value = server.read(p4_entity)
    assert(intended_value == observed_value)   # now passes
    ```
    Verified against a real bmv2 — `Bmv2PipelineSuite` asserts the switch returns
    exactly what went on the wire — plus unit tests on the write path that need no
    switch. Two details worth keeping: zero encodes as a single `0x00`, not the
    empty string (the spec defines zero as needing one bit, and "if the string's
    byte length is zero, the server always rejects the string"); and only
    *leading* zeros go, so `bytes(10, 0, 1, 1)` survives intact.

    Unsigned only, which is sufficient: P4Runtime v1 excludes `int<W>` from table
    key fields and action parameters, so there is no sign extension to undo. If
    `P4Data` support ever arrives, signed values need the other half of the rule.

## 9. Working on this build — two traps

**sbt 2's `test` is incremental and will report success having run zero tests.**
Always use `testFull` in CI and whenever you need certainty:

```
[info] Passed: Total 0, Failed 0, Errors 0, Passed 0   <- green, ran nothing
```

**sbt 2's action cache survives `clean`.** `clean` only removes `target/`; task
outputs are replayed from a machine-wide cache, so `clean; compile` can succeed in
seconds having compiled nothing — and emit zero warnings. For a genuinely cold
build (required before trusting warning counts or timings):

```bash
rm -rf ~/Library/Caches/sbt/v2 target   # macOS
rm -rf ~/.cache/sbt/v2 target           # Linux/XDG
```

On macOS, `~/.cache/sbt` may also exist and look plausible — it is not the
effective cache. `~/Library/Caches/sbt/v2` is.
