# P4R-Type architecture

How the pieces fit, and where the seams are. For the upgrade record and known
blockers see [UPGRADE.md](UPGRADE.md); for deferred work see [PROMPTS.md](PROMPTS.md).

---

## 1. The whole pipeline

P4R-Type sits between a P4 compiler and a P4 switch. Its one interesting claim is
that the **p4info file becomes Scala 3 types**, so a dataplane change that renames
a match field breaks the controller at *compile* time rather than at 3am.

```mermaid
graph LR
  subgraph Dataplane["Dataplane (P4)"]
    p4src["quackmpp.p4<br/>(P4-16, v1model)"]
    p4c["p4c<br/>--target bmv2<br/>--arch v1model"]
    bmjson["quackmpp.json<br/>(bmv2 pipeline)"]
    p4info["quackmpp.p4info.json<br/>(P4Runtime schema)"]
  end

  subgraph Library["P4R-Type (this repo, published)"]
    typegen["typegen.generate<br/>p4info JSON to Scala"]
    gentypes["Generated types<br/>TableMatchFields / TableAction / ActionParams<br/>+ Chan + connect"]
    api["p4rtype API<br/>insert / read / write / delete"]
    pb["ScalaPB bindings<br/>p4.v1.p4runtime.*"]
  end

  subgraph Control["Control plane (QuackMPP)"]
    ctrl["Controller code"]
  end

  switch["simple_switch_grpc<br/>(bmv2, P4Runtime 1.3.0)"]:::ext

  p4src --> p4c
  p4c --> bmjson
  p4c --> p4info
  p4info --> typegen
  typegen --> gentypes
  gentypes --> ctrl
  api --> ctrl
  ctrl -->|"P4Runtime gRPC :9559"| switch
  bmjson -.->|"loaded into the switch<br/>(NOT via P4R-Type, see gap 1)"| switch
  pb --- api

  classDef ext fill:#f6f6f6,stroke:#bbb,stroke-dasharray:4 3,color:#555;
```

The dotted edge is the first architectural gap: **P4R-Type has no
`SetForwardingPipelineConfig`**, so it cannot install a pipeline. Something else
must load `quackmpp.json` into the switch (the mininet VM does this out of band).

## 2. The guarantee QuackMPP spec 003 depends on

`typegen` turns each match-field name into a **singleton string type**. Renaming
`bucket` in the P4 therefore changes the *type*, and the controller stops
compiling — the failure moves from runtime to build time.

```mermaid
sequenceDiagram
    autonumber
    participant Dev as Dataplane author
    participant P4C as p4c
    participant TG as typegen.generate
    participant SC as Scala compiler
    participant CI as CI

    Dev->>P4C: rename bucket to bucket_v2 in quackmpp.p4
    P4C->>TG: p4info with meta.quack.bucket_v2
    TG->>SC: case "QuackMPP.exchange" => ("meta.quack.bucket_v2", Exact)
    Note over SC: controller still passes<br/>("meta.quack.bucket", Exact(...))
    SC-->>CI: type error, build fails
    Note over CI: caught here, not at 3am<br/>against a live fabric
```

The emitted type for the fixture is exactly:

```scala
type TableMatchFields[TN] =
  TN match
    case "QuackMPP.exchange" => ("meta.quack.bucket", Exact) | "*"
    case "*" => "*"
```

Pinned by `QuackMppTypegenSuite`, which asserts both directions: the correct
snippet compiles, and a renamed field does not. A *control* test proves
`compileErrors` reports nothing for the valid snippet — without it, the negative
tests would pass even if the harness rejected everything.

### Exact vs. non-exact matches

A wrinkle worth knowing before writing a controller: exact fields are mandatory in
P4Runtime, the others may be omitted. `typegen` mirrors that, so the shapes differ:

```scala
TableEntry(..., ("meta.quack.bucket", Exact(bytes(0,7))), ...)              // exact: bare tuple
TableEntry(..., Some("hdr.ipv4.dstAddr", LPM(bytes(10,0,1,1), 32)), ...)    // lpm: Option
```

## 3. Build layout

Two sbt projects. Only `root` is published; the split exists because the examples
used to ship inside the library jar, 35 of them in the **default package**.

```mermaid
graph TD
  subgraph Root["root — io.github.oluies::p4rt-scala (PUBLISHED)"]
    srcApi["src/main/scala/api/<br/>p4rtype.scala"]
    srcTg["src/main/scala/typegen/<br/>package typegen"]
    protos["src/protobuf/<br/>p4runtime v1.5.0 + google/rpc"]
    gen["target/.../src_managed/<br/>ScalaPB output, 120 files"]
    tests["src/test/scala/<br/>QuackMppTypegenSuite, Bmv2WireSuite"]
  end

  subgraph Ex["examples — publish / skip := true"]
    exsrc["examples/src/main/scala/<br/>bridge, router, config1, ..."]
    exp4["examples/src/main/p4/<br/>quackmpp.p4"]
  end

  protos -->|"sbt-protoc + ScalaPB"| gen
  gen --> srcApi
  gen --> srcTg
  srcApi --> tests
  srcTg --> tests
  exsrc -->|"dependsOn"| Root
  exp4 -->|"p4c"| exsrc

  classDef skip fill:#f6f6f6,stroke:#bbb,stroke-dasharray:4 3,color:#555;
  class Ex skip;
```

Two traps encoded here, both learned the hard way:

- **`root` does NOT `.aggregate(examples)`.** Combined with
  `examples.dependsOn(root)` that hangs sbt 2 during project loading. CI names
  `examples/compile` explicitly instead.
- **`src/main/scala/protobuf/` no longer exists.** It used to hold 120 committed
  generated files, while `PB.protoSources` pointed at a directory that did not
  exist — so codegen produced nothing and the committed copies compiled. See
  [UPGRADE.md](UPGRADE.md) §2.

## 4. Testing: what is actually verified

Most of the suite is compile-and-typecheck. Only `Bmv2WireSuite` touches a wire.

```mermaid
graph TD
  t1["QuackMppTypegenSuite<br/>typegen output + compile-error guarantee"]
  t2["p4info-fixture CI job<br/>real p4c vs committed fixture"]
  t3["Bmv2WireSuite<br/>gRPC against real bmv2"]
  t4["examples/compile<br/>examples still build"]

  t1 -->|"no container"| fast["Runs in testFull<br/>always"]
  t4 --> fast
  t2 -->|"p4lang/p4c container"| ci["CI job"]
  t3 -->|"needs P4RT_BMV2 set<br/>p4lang/behavioral-model"| opt["Skipped unless<br/>P4RT_BMV2=host:port"]
```

`Bmv2WireSuite` is what turns [UPGRADE.md](UPGRADE.md) §5 from an argument into a
measurement — bmv2 answers `p4runtime_api_version = 1.3.0`, confirming the switch
is pre-v1.4.0 and that a v1.5.0 client can still drive it.

## 5. Running the containers

The images are **linux/amd64 only** — `p4lang` publishes no arm64. That decides
the local-vs-CI split:

| | runtime | arch | notes |
| --- | --- | --- | --- |
| GitHub Actions | Docker (preinstalled) | amd64 native | `ubuntu-latest` is amd64; no emulation |
| macOS (Apple silicon) | Apple `container` or Docker | amd64 **emulated** | works, but first start took ~3m36s |

CI uses Docker because that is what the runners have; nothing in the repo depends
on that choice.

### Locally, with Apple `container`

```bash
container system status >/dev/null 2>&1 || container system start   # not auto-started after reboot

# Regenerate the p4info fixture with real p4c
cd P4R-Type/examples/src/main/p4
container run --rm --platform linux/amd64 -v "$PWD:/w" -w /w docker.io/p4lang/p4c:latest \
  p4c --target bmv2 --arch v1model --p4runtime-files quackmpp.p4info.json -o . quackmpp.p4

# A switch to talk to. Note the `--`: --grpc-server-addr is target-specific, and
# without the separator bmv2 prints usage and exits.
container run -d --name bmv2 --platform linux/amd64 -p 9559:9559 \
  docker.io/p4lang/behavioral-model:latest \
  simple_switch_grpc --no-p4 -- --grpc-server-addr 0.0.0.0:9559

until nc -z localhost 9559; do sleep 1; done      # -d returns before the service is ready
cd ../../../.. && P4RT_BMV2=localhost:9559 sbt "testOnly *Bmv2WireSuite"
```

Three things that will cost you an hour otherwise:

1. **Connect to `localhost:9559`, not the IP `container ls` prints.** That IP is
   not routable from the macOS host, and it changes across restarts.
2. **`--` before `--grpc-server-addr`.** bmv2's target parser is separate from its
   general options; without it the container starts, prints usage, and dies — and
   the published port still answers, so `nc -z` says "open" for a service that is
   not there.
3. **sbt's server outlives your shell.** `Test / fork := false`, so an `export
   P4RT_BMV2=...` after sbt is already running never reaches the test. Run
   `sbt shutdown` first.

## 6. Known architectural gaps

1. **No `SetForwardingPipelineConfig`.** The API is connect / insert / read /
   write / delete only, so P4R-Type cannot install a pipeline — something else has
   to. QuackMPP's spec 003 regenerates types after a p4info change; deploying that
   change needs a capability this library does not have.
2. **No multicast or clone-session modelling.** `Replica`, `MulticastGroupEntry`
   and `CloneSessionEntry` exist in the generated bindings but not in the
   `p4rtype` API. An MPP fabric replicating across workers builds on
   `p4.v1.p4runtime.*` directly — and must use the *deprecated* `egress_port` arm
   of `Replica`'s oneof, because bmv2 is 1.3.0 ([UPGRADE.md](UPGRADE.md) §5).
3. **Single primary controller.** `connect` hardcodes election id
   `Uint128(high = 0, low = 1)`; role-based arbitration is not modelled.
4. **`typegen` output is per-p4info and belongs to the consumer.** It writes Scala
   source; the consumer compiles it into its own module. P4R-Type ships the
   mechanism, not anyone's types.
