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
| zio | 2.0.0 | **2.1.26** |
| zio-streams | (transitive) | **2.1.26, now explicit** |
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

`zio` itself is still used (`ZIOAppDefault`, `ZLayer`, `ZStream`, `ZSink`) and
remains. `zio-streams` had been arriving transitively via `zio-grpc-core` and is
now declared explicitly.

## 5. P4Runtime protos — NOT yet updated (open work)

Upstream `p4lang/p4runtime` is at **v1.5.0** (Feb 2026). The vendored protos were
compared against it:

| proto | status |
| --- | --- |
| `p4/v1/p4data.proto` | identical to v1.5.0 |
| `p4/config/v1/p4types.proto` | identical to v1.5.0 |
| `p4/v1/p4runtime.proto` | **behind** (793 vs 875 lines) |
| `p4/config/v1/p4info.proto` | **behind** (390 vs 479 lines) |

`p4info.proto` is missing at least `PlatformProperties` (added v1.4.0),
`platform_properties` on `PkgInfo`, and `TableActionCall`. These are additive
proto3 changes, so the current code keeps working, but the refresh has **not**
been done here — it is deferred to a focused session (see `PROMPTS.md`), because
regenerating against v1.5.0 changes the generated API surface and wants its own
review.

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
`src/main/scala/examples/quackmpp_exchange.p4info.json` was written by hand in the
exact JSON shape `p4c --target bmv2 --arch v1model --p4runtime-files` emits
(protobuf JSON mapping: `pkgInfo.arch: "v1model"`, `matchFields[].matchType: "EXACT"`,
p4c-style type-prefixed ids). It declares table `QuackMPP.exchange` with an
exact match on `meta.quack.bucket` and actions `set_worker(worker_id, port)`,
`drop`, `NoAction`.

`typegen` emits (committed as `quackmpp_exchange.scala`, and it compiles):

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

publishes to `~/.ivy2/local`. Current coordinates (from `build.sbt`):

```
organization : (unset — defaults to "p4rt-scala")
name         : p4rt-scala
version      : 0.1.0-SNAPSHOT
scala        : 3.8.4  ->  p4rt-scala_3
```

⚠️ **`organization` is not set in `build.sbt`.** Before QuackMPP depends on this,
set a real one (e.g. `organization := "io.github.oluies"`), because
`"p4rt-scala" %% "p4rt-scala" % "0.1.0-SNAPSHOT"` is not a coordinate anyone should
publish. Mill then depends on it via:

```scala
def ivyDeps = Agg(ivy"io.github.oluies::p4rt-scala:0.1.0-SNAPSHOT")
```

with `~/.ivy2/local` resolved (Mill resolves it by default for local Ivy).

**Generated types are per-p4info and belong to the consumer.** `typegen` writes
Scala to stdout; QuackMPP should run it as a build step against its own p4info and
compile the output into its own module, rather than expecting P4R-Type to ship
QuackMPP's types. The `quackmpp_exchange.*` files here are a *fixture proving the
mechanism*, not the artifact QuackMPP should import.

## 8. Blockers / friction for QuackMPP spec 003

1. **Pre-release dependency chain (§3).** sbt 2 forces sbt-protoc RC2 + ScalaPB
   alpha.6 + a json4s eviction override. If QuackMPP wants a boring dependency,
   drop to sbt 1.12.13 — everything is stable there and the library API is
   unchanged either way.
2. **`organization` is unset (§7).** Blocks publishing a sane coordinate.
3. **Protos are behind v1.5.0 (§5).** Additive-only so far, but bmv2 feature work
   (e.g. platform properties) will want the refresh.
4. **There is no real test suite upstream.** The inherited suite was the sbt
   template's `assertEquals(42, 42)`. `testFull` being green proved nothing before
   `QuackMppTypegenSuite` was added. Treat upstream green as unverified.
5. **`typegen` output is stdout-only.** There is no sbt task to regenerate types
   into a source dir; callers shell out and redirect (CI does exactly this to check
   for drift). QuackMPP's Mill build will need to wrap this itself.
6. **The examples require the mininet VM.** Everything under `src/main/scala/examples/`
   except the p4info fixtures connects to a running switch, so it cannot be
   exercised in CI. The typegen fixture is CI-safe precisely because it only
   compiles.
7. **`Chan`/`connect` hardcode an election id** of `Uint128(high=0, low=1)` and a
   single primary client. Multi-controller / role-based arbitration (which an MPP
   fabric may want) is not modelled.
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
