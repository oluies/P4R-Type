# Follow-up session prompts

Dependency work deliberately **not** done in the upgrade session, because each
item changes a generated API surface or a resolution chain and wants its own
review. Context for all of them is in `UPGRADE.md`.

Run these one per session, in this order.

---

## Prompt 1 — Set a real `organization` and publish coordinates (small, do first)

> In `P4R-Type/build.sbt`, `organization` is unset, so `publishLocal` produces the
> coordinate `p4rt-scala %% p4rt-scala % 0.1.0-SNAPSHOT`. QuackMPP (a Mill build)
> needs to depend on this artifact.
>
> Set `organization := "io.github.oluies"` (confirm the right groupId with me
> first if you think another is better — it must be one we can eventually publish
> to Maven Central under). Then:
> 1. Run `cd P4R-Type && sbt -batch publishLocal` and report the exact artifact
>    path written under `~/.ivy2/local`.
> 2. Verify the published jar actually contains `p4rtype.*` and the generated
>    `p4.v1.p4runtime.*` classes (`unzip -l` the jar) — the ScalaPB codegen writes
>    to `sourceManaged`, so confirm generated classes are packaged and not dropped.
> 3. Write the exact Mill `ivyDeps` line QuackMPP should use into `UPGRADE.md` §7,
>    replacing the placeholder, and state whether Mill needs an explicit
>    `~/.ivy2/local` resolver.
>
> Do not change any dependency versions in this session.

---

## Prompt 2 — Refresh the P4Runtime protos to v1.5.0

> `P4R-Type/src/protobuf/` vendors P4Runtime protos that are behind upstream
> `p4lang/p4runtime` v1.5.0. Per `UPGRADE.md` §5: `p4data.proto` and
> `p4types.proto` are already identical to v1.5.0; `p4runtime.proto` (793 vs 875
> lines) and `p4info.proto` (390 vs 479 lines) are behind, missing at least
> `PlatformProperties`, `PkgInfo.platform_properties`, and `TableActionCall`.
>
> Note: the ScalaPB codegen is now live (`PB.protoSources := src/protobuf`), so
> editing these protos really does regenerate the API — unlike before this fork's
> upgrade, when codegen silently generated nothing.
>
> 1. Replace all four protos with the v1.5.0 versions from
>    `https://raw.githubusercontent.com/p4lang/p4runtime/v1.5.0/proto/...`,
>    keeping `google/protobuf/any.proto` and `google/rpc/status.proto` consistent
>    with whatever protobuf-java ScalaPB 1.0.0-alpha.6 pulls (currently 4.35.0).
> 2. `sbt -batch "compile; testFull"` and fix fallout in `src/main/scala/api/` and
>    `src/main/scala/typegen/`. Report every source change the new protos forced.
> 3. Confirm the `bucket` fixture still round-trips:
>    `sbt -batch "runMain parseP4info src/main/scala/examples/quackmpp_exchange.p4info.json quackmpp"`
>    must still produce `case "QuackMPP.exchange" => ("meta.quack.bucket", Exact) | "*"`,
>    and `QuackMppTypegenSuite` must stay green.
> 4. Cross-check against what bmv2's `simple_switch_grpc` actually speaks (its PI
>    dependency), and record in `UPGRADE.md` whether v1.5.0 protos are safe against
>    the bmv2 version QuackMPP targets, or whether we should pin to v1.4.1.
>
> Beware: `diff` is aliased to `git diff` in this shell — use `/usr/bin/diff`.

---

## Prompt 3 — Escape the pre-release dependency chain (the important one)

> Per `UPGRADE.md` §3, building P4R-Type on **sbt 2.0.2** currently requires four
> pre-release artifacts and one safety override:
> - `sbt-protoc` **1.1.0-RC2** (no stable sbt 2 release exists — `sbt-protoc_sbt2_3`)
> - ScalaPB `compilerplugin` / `scalapb-runtime` / `-grpc` **1.0.0-alpha.6**
>   (0.11.x is unusable under sbt 2: `compilerplugin_3` 0.11.x → `protoc-gen_2.13`
>   → `protoc-bridge_2.13`, which conflicts with sbt-protoc's `protoc-bridge_3`)
> - `scalapb-json4s` **1.0.0-alpha.1** — the only 1.0-line release; it pins
>   `scalapb-runtime` 1.0.0-alpha.1 while we run alpha.6, which needs
>   `evictionErrorLevel := Level.Warn` to resolve at all.
>
> That last one is a real cross-alpha binary-compatibility gamble. `typegen`'s
> p4info parsing works today, but the rest of the json4s surface is unverified.
>
> Task:
> 1. Re-check Maven Central for newer releases: `sbt-protoc_sbt2_3` 1.1.0 final,
>    ScalaPB `compilerplugin_3` 1.0.0 final / newer alpha, a `scalapb-json4s`
>    release beyond 1.0.0-alpha.1, and any zio-grpc release with an sbt 2 codegen.
>    Use `maven-metadata.xml` directly — the solrsearch index returns stale results.
> 2. If a consistent stable set now exists, move to it and delete
>    `evictionErrorLevel := Level.Warn`.
> 3. If not, evaluate **dropping `scalapb-json4s` entirely**. It is used in exactly
>    one place — `parseP4info` in `src/main/scala/typegen/typegen.scala`:
>    ```scala
>    val parser = scalapb.json4s.Parser()
>    val p4info: P4Info = parser.fromJsonString[P4Info](lines)
>    ```
>    Assess replacing it with protobuf-java's `com.google.protobuf.util.JsonFormat`
>    (parse into the Java `P4Info` message, then convert), or with reading p4c's
>    **text-format** p4info via `TextFormat` instead of JSON. Either removes the
>    single artifact forcing the eviction override. Report effort and risk; do not
>    implement without checking back.
> 4. Then present the trade-off explicitly: stay on sbt 2.0.2 with whatever
>    pre-release surface remains, vs. drop to **sbt 1.12.13** where the entire chain
>    is stable (sbt-protoc 1.0.8, ScalaPB 0.11.20, scalapb-json4s 0.12.2, zio-grpc
>    0.6.3, all on `protoc-bridge_2.12`). The library API is identical either way.
>    Recommend one.
>
> Verify any change with a genuinely cold build — sbt 2's action cache survives
> `clean`: `rm -rf ~/Library/Caches/sbt/v2 target` first, and use `testFull`, never
> `test` (sbt 2's `test` is incremental and reports success having run 0 tests).

---

## Prompt 4 — Decide whether zio is still worth a dependency

> `zio-grpc` was removed during the upgrade (`UPGRADE.md` §4) — it has no sbt 2
> compatible codegen, and its only uses were three unused imports.
>
> What remains of ZIO is `typegen.scala`'s use of `ZIOAppDefault`, `ZLayer`,
> `RIO`, `ZStream`, `ZSink` — for a batch program that reads one file, generates
> Scala source, and prints it to stdout. It pulls `zio` + `zio-streams` (2.1.26).
>
> Assess replacing that with plain Scala (`IO`-free `Either`/exceptions +
> `println`), which would drop two dependencies and make `typegen` trivially
> callable as a library from a Mill build — see `UPGRADE.md` §8 blocker 5, where
> QuackMPP has to shell out and redirect stdout because there is no library entry
> point.
>
> Deliver: a recommendation plus, if favourable, a `typegen` that exposes a
> `def generate(p4infoJson: String, packageName: String): Either[String, String]`
> library entry point alongside the existing main. Keep `QuackMppTypegenSuite`
> green and the emitted types byte-identical.
