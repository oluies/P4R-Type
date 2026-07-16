# P4R-Type upgrade record

This fork of [JensKanstrupLarsen/P4R-Type](https://github.com/JensKanstrupLarsen/P4R-Type)
(the OOPSLA artifact) is being brought up to date to serve as the control-plane
binding library for **QuackMPP**.

Verified on **JDK 25.0.3 / sbt 2.0.2 / Scala 3.8.4**, cold action cache:
`compile` and `testFull` both green, 4/4 tests passing.

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
| sbt | 1.7.1 | **2.0.2** |
| Scala | 3.1.3 | **3.8.4** (Next track) |
| JDK | unpinned (broken on 25/26) | **25 LTS** (CI-pinned) |
| sbt-protoc | 1.0.3 *and* 1.0.2 (declared twice) | **1.1.0-RC2** |
| ScalaPB compilerplugin | 0.11.11 | **1.0.0-alpha.6** |
| scalapb-runtime / -grpc | 0.11.11 | **1.0.0-alpha.6** |
| scalapb-json4s | 0.12.0 | **1.0.0-alpha.1** |
| zio-grpc-codegen | 0.6.0-test4 | **removed** (see §4) |
| zio | 2.0.0 | **removed** (see §4) |
| zio-streams | (transitive) | **removed** (see §4) |
| grpc-netty | 1.41.0 (and 1.41.0 again) | **1.82.2** |
| protobuf-java | (via ScalaPB 0.11.11) | **4.35.0** (via ScalaPB alpha.6) |
| munit | 0.7.29 | **1.3.4** |
| sbt-bloop (`project/metals.sbt`) | 1.5.8, committed | **removed + gitignored** |
| P4Runtime protos | ~v1.3-era | **unchanged — see §5** |

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
Nothing was lost. (The build now generates 117: `Any.scala` / `AnyProto.scala`
also dropped out, see below.)

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
- `scalapb-json4s` — required by `typegen` to parse p4info JSON — only publishes
  **1.0.0-alpha.1** on the 1.0 line, and it pins `scalapb-runtime` 1.0.0-alpha.1.
  Against runtime alpha.6 this is an eviction sbt treats as an error, so the build
  declares a version scheme for that one artifact:
  ```scala
  libraryDependencySchemes +=
    "com.thesamet.scalapb" %% "scalapb-runtime" % VersionScheme.Always
  ```
  This is deliberately scoped rather than a blanket `evictionErrorLevel :=
  Level.Warn`, which would also silence every *future* binary-incompatible
  eviction (grpc, zio, protobuf-java) that Scala Steward bumps into the build —
  precisely the class of problem eviction errors exist to catch.

  **This is the main fragility of the sbt 2 choice.** It is not theoretical
  hand-waving: it is a real cross-alpha binary-compatibility gamble. In practice
  the path we exercise works — `parseP4info` parses a p4c v1model p4info and emits
  correct types (§6) — but the rest of the json4s API surface is unverified.

**If this fragility is unacceptable, sbt 1.12.13 is the escape hatch**, and every
artifact above resolves stable there: sbt-protoc 1.0.8, compilerplugin 0.11.20,
scalapb-runtime 0.11.20, scalapb-json4s 0.12.2, zio-grpc 0.6.3 — all consistently
on `protoc-bridge_2.12`. sbt 1.12.13 also fixes the JDK 25/26 breakage. The only
thing lost is sbt 2 itself.

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
- **`p4info.proto` is purely additive** — no field removed, none renumbered. New:
  `PlatformProperties` + `PkgInfo.platform_properties` (v1.4.0), `TableActionCall`,
  `Table.initial_default_action`, `ActionProfile.max_member_weight`.
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

### Is v1.5.0 safe against bmv2?

Yes, and there is no need to pin v1.4.1. bmv2's `simple_switch_grpc` gets its
P4Runtime from PI, which vendors `p4lang/p4runtime` as a git submodule pinned at
commit `ec4eb5e` (2024-08-18) — *before* the v1.4.0 release (2024-09-13). So bmv2
effectively speaks ~v1.3.0-era P4Runtime, i.e. **our client protos are now ahead of
the switch**.

That is fine, because: the RPC set is identical; P4Runtime v1 is proto3, so a
server ignores fields it does not know; and P4R-Type does not populate any of the
post-v1.3 additions. A newer client talking to an older server is only a problem if
the client *depends on* new fields, which it does not.

### Warning noise

A cold build (action cache cleared — see §9) emits **856 warnings, 799 of them
from ScalaPB-generated code**: ScalaPB 1.0.0-alpha.6's codegen still emits
`_` as a type wildcard (560) and `private[this]` (272), both of which Scala 3.8
warns about. The remaining 57 are in `src/main/scala/examples` (32) and
`src/main/scala/api` (25). All benign; none are errors. If the noise becomes a
problem, extend the existing `-Wconf` rule to silence `src_managed` rather than
editing generated output.

## 6. typegen verified on a p4c v1model p4info

`p4c` is not installed on the dev machine, so the fixture
`src/test/resources/quackmpp_exchange.p4info.json` was written by hand in the
exact JSON shape `p4c --target bmv2 --arch v1model --p4runtime-files` emits
(protobuf JSON mapping: `pkgInfo.arch: "v1model"`, `matchFields[].matchType: "EXACT"`,
p4c-style type-prefixed ids). It declares table `QuackMPP.exchange` with an
exact match on `meta.quack.bucket` and actions `set_worker(worker_id, port)`,
`drop`, `NoAction`.

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

| contents | count |
| --- | --- |
| `p4rtype/*` (the hand-written API) | 29 |
| `p4/v1/p4runtime/*` (generated) | 417 |
| `p4/config/v1/p4info/*` (generated) | 175 |

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
6. **The examples require the mininet VM.** Everything under `src/main/scala/examples/`
   except the p4info fixtures connects to a running switch, so it cannot be
   exercised in CI. The typegen fixture is CI-safe precisely because it only
   compiles.
7. **`Chan`/`connect` hardcode an election id** of `Uint128(high=0, low=1)` and a
   single primary client. Multi-controller / role-based arbitration (which an MPP
   fabric may want) is not modelled.
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
8. **Scala Steward's PRs are not CI-gated.** The workflow uses the default
   `GITHUB_TOKEN`, and PRs opened with it do not trigger further workflow runs —
   so `ci.yml` will not run on dependency bumps, which given §3 are the PRs that
   most need it. Needs a PAT or GitHub App token (`github-app-id` /
   `github-app-key`); both require repo secrets, so this is a decision for the
   repo owner. Marked inline in `.github/workflows/scala-steward.yml`.

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
