# P4R-Type artifact instructions

[![CI](https://github.com/oluies/P4R-Type/actions/workflows/ci.yml/badge.svg)](https://github.com/oluies/P4R-Type/actions/workflows/ci.yml)

> **This is a fork.** It has been upgraded from the original OOPSLA artifact
> (Scala 3.1.3 / sbt 1.7.1, which no longer builds on current JDKs) to
> **Scala 3.8.4 / sbt 2.0.3 / JDK 25 (LTS baseline) or 26 — CI builds both**,
> to serve as the control-plane binding
> library for QuackMPP.
>
> * **[ARCHITECTURE.md](ARCHITECTURE.md)** — how the pieces fit (diagrams), how to
>   run p4c/bmv2 in containers, and the known architectural gaps. Start here.
> * **[UPGRADE.md](UPGRADE.md)** — version record, the sbt-vs-Mill decision, bmv2
>   compatibility analysis, and blockers.
> * **[PROMPTS.md](PROMPTS.md)** — follow-up work, and what is already done.
> * [Related work, and what else the types could check](#related-work-and-what-else-the-types-could-check)
>   — below: the research landscape, and the p4info facts `typegen` currently discards.
>
> Two things that will bite you if you skip them:
> * Under sbt 2 you must run `sbt "testFull"`, not `sbt test` — `test` is
>   incremental and will report success having run **zero** tests.
> * sbt 2's action cache survives `clean`. For a genuinely cold build:
>   `rm -rf ~/Library/Caches/sbt/v2 target` (macOS) / `~/.cache/sbt/v2` (Linux).

This repository contains:

  * In `P4R-Type/`: The code for the P4R-Type API and type generator, as well as examples that use the P4R-Type API.
  * In `vm/`: A virtual machine that sets up a simulation network using mininet, which can be used to test the API.
  * In `container/`: A containerised `simple_switch_grpc` plus p4c, for the things that do not need a
    network — regenerating the p4info fixture and exercising the P4Runtime wire. **It is not a
    replacement for `vm/`** (no mininet, no topology, different p4c/bmv2 versions);
    see [ARCHITECTURE.md](ARCHITECTURE.md) §6 for the comparison.

## Building and testing

Nothing here needs the VM or a container. From `P4R-Type/`:

    sbt "compile; Test/compile; testFull; examples/compile"

These are the same commands CI runs (CI adds `-batch`). Note `testFull`, not
`test` — see the warning above; and `examples/compile` is separate because
`root` deliberately does not aggregate `examples`.

The bmv2-backed suites skip themselves unless their environment variables are
set, and **say so** rather than passing silently:

    [info] Bmv2WireSuite skipped: set P4RT_BMV2=host:port to run it
    [info] Bmv2PipelineSuite skipped: set P4RT_BMV2=host:port and P4RT_BMV2_JSON=/path/to/quackmpp.json

### Running bmv2 and p4c in a container

`container/p4rt.sh` works with **either Docker or Apple's `container`**. It
picks whichever is on `PATH`, preferring `container`; override with
`RUNTIME=docker` or `RUNTIME=container`. Run from the repo root:

| Command | What it does |
|---|---|
| `container/p4rt.sh up` | start `simple_switch_grpc` on `localhost:9559`, waiting until it actually answers |
| `container/p4rt.sh down` | stop it |
| `container/p4rt.sh test` | `up`, then run `Bmv2WireSuite` against it |
| `container/p4rt.sh pipeline-test` | `up`, push a pipeline, insert and read back a table entry |
| `container/p4rt.sh gen` | recompile the `.p4` files and refresh the committed p4info fixtures |
| `container/p4rt.sh gen-types` | regenerate the committed Scala types from those fixtures |
| `container/p4rt.sh gen-vm` | compile `quackmpp.p4` with the VM-era p4c into a side-by-side `quackmpp.vm-era.p4info.json` for comparison — does **not** touch the committed fixtures |

There is also `container/compose.yaml`, but it is **Docker only** — Apple's
`container` has no compose support (`container compose` reports
`Plugin 'container-compose' not found`), which is why the script exists.

Two things worth knowing on an Apple-silicon Mac:

  * p4lang publishes `linux/amd64` images only, so everything runs emulated. The
    script passes `--platform linux/amd64` explicitly; the first p4c run took
    about 3m36s on an M-series machine.
  * Apple's daemon is not started automatically and does not survive a reboot.
    The script runs `container system start` for you — without it every
    subcommand fails with an opaque XPC error that reads like a broken install.

After `gen`/`gen-types`, re-run the test suite: the drift tests compare the
committed types against what `typegen` emits, and CI compares the committed
p4info against fresh `p4c` output.

### What CI checks

[`.github/workflows/ci.yml`](.github/workflows/ci.yml), on every push and pull
request:

  * **Build (JDK 25)** and **Build (JDK 26)** — the full command above, on both
    the LTS baseline and the current JDK. `fail-fast: false`, so a break on one
    and not the other is visible rather than cancelled.
  * **p4info fixture matches real p4c** — recompiles every committed `.p4` with
    `p4lang/p4c` and diffs the result against the committed fixture, so a newer
    p4c emitting a field we do not model is caught rather than discovered later.

All three are required status checks on `main`, which also rejects force-pushes
and deletion. Administrators are exempt, so direct pushes still work — CI is
blocking for everyone else, advisory for repo admins.

Dependency updates arrive as CI-gated PRs from
[Scala Steward](.github/workflows/scala-steward.yml); GitHub Actions versions
come from Dependabot, since Steward does not cover them. Neither proposes JDK
upgrades, which is why the JDK matrix above is maintained by hand.

### Releases

Pushing a `v*` tag runs [`.github/workflows/release.yml`](.github/workflows/release.yml),
which tests, signs and uploads the artifact to Maven Central; the published
version is derived from the tag, so the two cannot disagree. The same workflow
has a dry-run mode that does everything except upload. Signing keys and the
Portal token are repo secrets — releasing never touches a developer machine.
See [PUBLISHING.md](PUBLISHING.md).

**Note for OOPSLA artifact version:** As an alternative to building the VM image with Vagrant, the top folder also contains the file `P4R-Type_Demo_VM.ova`, a ready-to-use VM image. It can be imported with the VirtualBox interface using the default settings. In case you use this method for setting up the VM, skip the first three steps of the **Kick-the-Tires Guide** and start the VM directly from VirtualBox instead.

The OOPSLA artifact version which includes the VM image can be found [here](https://dl.acm.org/do/10.1145/3580420).

## Prerequisites

__This artifact requires:__

  * [sbt](https://www.scala-sbt.org/) (for building the P4R-Type API and type generator)
  * [VirtualBox](https://www.virtualbox.org/wiki/Downloads) (for running the examples)
  * [Vagrant](https://www.vagrantup.com/) (for running the examples)

VirtualBox and Vagrant are needed only for the paper's examples, which use the
mininet network in `vm/`. To build, run the test suite, or exercise the
P4Runtime wire against a real bmv2, you need only sbt and a container runtime —
Docker or Apple's `container`. See [Building and testing](#building-and-testing).

## Kick-the-Tires Guide

From now on, we write `$ROOT` to denote the root directory of the artifact.

  1. Navigate to the `$ROOT/vm/` directory.
  2. Run `vagrant up` to build and run the VM. Building the VM will take 10-15 minutes.
  3. When the `vagrant` building procedure is complete, the VM will reboot.
     (From now on, you can launch the VM from VirtualBox, without using `vagrant` again.)
  4. When the VM presents a graphical log-in prompt:
      1. Log on as user __P4RType__ with the password `sdn`.
      2. Open a terminal in the VM and run `make test` (in the home directory of the user P4RType).
         This will start the mininet network simulation with four hosts and four switches `s1`..`s4`
         (see the `topology.json` file for the layout).
         It also applies the P4 configuration `config1` to `s1` and `s2`, and `config2` to `s3` and `s4`.
         You will see the `mininet>` prompt and the message `Ready to receive requests!`.
  5. Now, on the **host** machine that is running the VM, navigate to the `$ROOT/P4R-Type/` directory.
  6. Run `sbt "examples/runMain p4rtypeTest"`. This will compile and run the program
     `examples/src/main/scala/p4rtypetest.scala`, which connects to the mininet network in the VM and
     sends some test queries to the `s1` switch.

If everything goes well, after the last step you will see `Test successful!` followed by `[success]`.
**NOTE**: you may also see the following message, that **you can ignore**:

    [ERROR] io.grpc.StatusRuntimeException: UNAVAILABLE: Channel shutdown

At this point, the artifact should be working.

## Project layout

`$ROOT/P4R-Type/` is an sbt build with two projects:

**`root`** — the library, and the only thing published. Its sources live in
`src/main/scala/`:

  * `typegen/`: The P4R-Type type generator, which parses a given P4info file and outputs corresponding Scala 3 types
    (as per the encoding described in Definition 5.2 of the companion paper). Also exposes a
    `typegen.generate(p4infoJson, packageName): Either[String, String]` entry point for build tools.
  * `api/`: The type-parametric P4R-Type API used for making P4Runtime queries, outlined in Section 7 of the companion paper.

  The "untyped" P4Runtime API code is **generated at build time** by
  [ScalaPB](https://scalapb.github.io/docs/installation) from the protobuf specification in
  `P4R-Type/src/protobuf/`, and lands in `target/.../src_managed/`. It is no longer
  committed under `src/main/scala/protobuf/` — see [UPGRADE.md](UPGRADE.md) §2.

**`examples`** — in `examples/src/main/scala/`, run with `sbt "examples/runMain <name>"`.
Examples that use the P4R-Type API with generated types; all of them require the
P4R-Type VM to be running. This is a separate project that is **never published**,
so the examples and their generated `config*` types stay out of the library jar
([UPGRADE.md](UPGRADE.md) §8.8).

For inspecting the code, we recommend using [VS Code](https://code.visualstudio.com/)
with the [Scala Metals](https://scalameta.org/metals/docs/editors/vscode) extension,
in order to explore the files more easily and view type errors in real-time.

## Type generation

The following instructions must be followed from inside the directory `$ROOT/P4R-Type/`.

To compile a P4info file into a Scala 3 package, use

    sbt "runMain typegen.parseP4info <p4info-file> <package-name> [output.scala]"

where `<p4info-file>` is the relative path of the P4info file to be compiled, and
`<package-name>` is the name of the package to be generated.
With no output path the generated Scala package is written to stdout; with one it
is written to that file.

> Prefer the output path over redirecting stdout. sbt interleaves its own log
> lines into stdout and colourises them in CI, so `sbt "runMain ..." > Foo.scala`
> produces a file with `[info]` lines and ANSI escapes in it (`--error` does not
> help — the thin client still prints).

The two CLI forms above are for humans. **Build tools should not shell out to
`parseP4info` at all** — neither the stdout form nor the output-path form. Call
the library entry point directly, which is what `QuackMppTypegenSuite` does:

```scala
typegen.generate(p4infoJson: String, packageName: String): Either[String, String]
```

As an example of how to generate a package, navigate to the `$ROOT/P4R-Type/` directory and run:

    sbt "runMain typegen.parseP4info examples/src/main/scala/config1.p4info.json config1"

This will generate a Scala package based on the configuration in the `config1.p4info.json` file
and write it to stdout.  The generated Scala package contains:

* A set of match types capturing the dependencies between P4Runtime entities (tables, actions, ...)
* A `connect` function, which establishes a connection to a P4Runtime server and returs a `Chan`nel
* A `Chan` class, usable to perform the P4Runtime operations (insert, delete, ...) supported by
  our P4R-Type API.

### Generated match types

This section outlines the match types generated by our tool, which are also described in the paper.

As an example, consider a P4Info file with these tables and actions:

    "tables": [
      {
        "preamble": {
          "id": 50014192,
          "name": "Process.ipv4_lpm",
          "alias": "ipv4_lpm"
        },
        "matchFields": [
          {
            "id": 1,
            "name": "hdr.ipv4.dstAddr",
            "bitwidth": 32,
            "matchType": "LPM"
          }
        ],
        "actionRefs": [
          { "id": 26706864 },
          { "id": 22338797 }
        ],
        "size": "1024"
      }
    ],
    "actions": [
      {
        "preamble": {
          "id": 22338797,
          "name": "Process.drop",
          "alias": "drop"
        }
      },
      {
        "preamble": {
          "id": 26706864,
          "name": "Process.ipv4_forward",
          "alias": "ipv4_forward"
        },
        "params": [
          {
            "id": 1,
            "name": "dstAddr",
            "bitwidth": 48
          },
          {
            "id": 2,
            "name": "port",
            "bitwidth": 9
          }
        ]
      }
    ]

This would produce the following types:

    type TableMatchFields[TN] =
      TN match
        case "Process.ipv4_lpm" => (Option[("hdr.ipv4.dstAddr", LPM)]) | "*"
        case "*" => "*"
    type ActionName = "Process.drop" | "Process.ipv4_forward" | "*"

    type TableAction[TN] <: ActionName =
      TN match
        case "Process.ipv4_lpm" => "Process.ipv4_forward" | "Process.drop" | "*"
        case "*" => "*"

    type ActionParams[AN] =
      AN match
        case "Process.drop" => Unit
        case "Process.ipv4_forward" => (("dstAddr", ByteString), ("port", ByteString))
        case "*" => "*"

### The `connect` function

The `connect` function connects the controller to a target (i.e. a device that
supports P4 and P4Runtime, e.g. a network switch), returning a `Chan` object
representing the connection (see below).

Why is the `connect` function generated as part of a package, and not
type-parametric like the other P4R-Type API functions? This is done to instantiate
the type parameters of `Chan` objects with the aforementioned match types, according
to the P4Info of the target device.  This way, we can hide the complexity from the user
--- who is only required to invoke `<package>.connect(...)` (where `<package>` is
tenerated from the P4 configuration of the switch they are trying to connect to).

### The `Chan` class

A `Chan` object represents a connection between a target (i.e. a device that
supports P4 and P4Runtime, e.g. a network switch) and a controller (the program
acting as P4Runtime client). The `Chan` class generated by our tool inherits from
an abstract `Chan` class in the `p4rtype` package, which is type-parametric.
The generated `Chan` class is always instantiated with the match types from its
own package. By doing this, the type-parametric P4R-Type API functions that
take `Chan` objects (e.g. `insert`, `delete`, ...) will have their types
constrained by the types from the associated package: this ensures the correctness
of API calls, and also helps the Scala compiler infer the type arguments whitout
the user having to provide them explicitly.

Internally, the `Chan` class contains two functions, `toProto` and `fromProto`,
which convert table entries from their strongly-typed P4R-Type representation to
their underlying "loosely-typed" protobuf representations, and vice versa.

## Reproducing the examples in the companion paper

The following instructions must be followed from inside the directory `$ROOT/P4R-Type/`.

The instructions for running all examples are given below.  In general, each example can be run by launching:

    sbt "examples/runMain <main-function>"

where `<main-function>` is the `@main` function to be run
(usually named the same as the example file itself).

**NOTE**: when execution of an example terminates, the connection to each target device is closed,
which causes the following message that **you can ignore**:

    [ERROR] io.grpc.StatusRuntimeException: UNAVAILABLE: Channel shutdown

### IMPORTANT: before you try any of the examples below

All provided examples require the P4R-Type VM to be running.  **Moreover**, each example assumes
a clean configuration where the network switches have no preconfigured table entries.

For this reason, **before and in-between running any of the examples below**, you need to perform
the following steps **on the VM**:
1. if the mininet network simulation is already running, close it (Ctrl+d);
2. run `make clean`;
3. run `make build`;
4. run `make network`.

### Testing the effect of running the examples

All of the examples described below affect the connectivity of the network to some degree.
To verify that the examples have had any effect, you can use e.g. a `ping` command at the
`mininet>` prompt on the VM, such as:

    h1 ping h2

With this command, host `h1` will then attempt to periodically send packets to `h2`. The
sending of packets can be stopped with Ctrl+c.

If you attempt a `ping` command _before_ running one of the examples below, you will see
no output: this is because there is no route between any of the hosts in the virtual network
simulated by `mininet`.

After you run one of the examples below, the P4 tables of the devices in the virtual network
will be updated, and packets will be able to flow (at least along some routes)  As a
consequence, a `ping` command like the one above will produce an output similar to:

    mininet> h1 ping h2
    PING 10.0.2.2 (10.0.2.2) 56(84) bytes of data.
    64 bytes from 10.0.2.2: icmp_seq=1 ttl=62 time=6.15 ms
    64 bytes from 10.0.2.2: icmp_seq=2 ttl=62 time=3.43 ms
    64 bytes from 10.0.2.2: icmp_seq=3 ttl=62 time=3.27 ms
    ...

### Simple IPv4 table update (Fig. 1 in the companion paper)

The example can be found in `$ROOT/P4R-Type/examples/src/main/scala/forward_c1.scala`
(with the erroneous, non-compiling code commented out) and can be executed by running
(on the host machine, from inside the directory `$ROOT/P4R-Type/`):

    sbt "examples/runMain forward_c1"

__Effect__: The program will insert table entries for `s1` and `s2` such that
`h1` and `h2` can communicate with (ping) each other. To test connectivity, use

    h1 ping h2

To see how the update changes connectivity, run the `ping` command _before_
running the control program, and observe how the pings only start to succeed
after running the program.

### Second simple table update

The example can be found in `$ROOT/P4R-Type/examples/src/main/scala/forward_c2.scala`
and can be executed by running (on the host machine, from inside the directory
`$ROOT/P4R-Type/`):

    sbt "examples/runMain forward_c2"

__Effect__: The program will insert table entries for `s3` and `s4` such that
`h3` and `h4` can communicate with (ping) each other. To test connectivity, use

    h3 ping h4

To see how the update changes connectivity, run the `ping` command _before_
running the control program, and observe how the pings only start to succeed
after running the program.

### Full connectivity

The example can be found in `$ROOT/P4R-Type/examples/src/main/scala/bridge.scala` and
can be executed by running (on the host machine, from inside the directory
`$ROOT/P4R-Type/`):

    sbt "examples/runMain bridge"

__Effect__: The program will insert table entries for all switches such that
each host can communicate with any other host. To test connectivity, use

    h1 ping h4

To see how the update changes connectivity, run the `ping` command _before_
running the control program, and observe how the pings only start to succeed
after running the program.

### Multi-switch update (Fig. 16 in the companion paper)

The example can be found in `$ROOT/P4R-Type/examples/src/main/scala/firewall.scala`
and can be executed by running (on the host machine, from inside the directory
`$ROOT/P4R-Type/`):

    sbt "examples/runMain firewall"

__Effect__: The program will first establish full connectivity between hosts
(using the previous example), then insert table entries into the `firewall` table in
each switch, causing packets with destination addresses to `h1` or `h4` to be
dropped. Effectively, this means that communication is only possible between
`h2` and `h3`. To test connectivity, use

    h2 ping h3

To see how the update changes connectivity, run the `ping` command _before_
running the control program, and observe how the pings only start to succeed
after running the program.

### Port forwarding management (Section 8.2 in the companion paper)

The example can be found in `$ROOT/P4R-Type/examples/src/main/scala/router.scala`
and can be executed by running (on the host machine, from inside the directory
`$ROOT/P4R-Type/`):

    sbt "examples/runMain router"

__Effect__: The program first establishes full connectivity between hosts, then
provides a CLI for inserting network address translation rules into `s4`.
For the purpose of this example, we assume that `h1` to `h3` are subnets
part of the same local network, and `h4` is an external network with a separate
address scheme.

For this example, first start mininet (in the VM) by running

    make test_nat

_Then_, start the port forwarding program (to set up full connectivity).
Now, try to use the CLI to insert a rule. Each rule needs an external IP and port to
map to, and the corresponding internal host (which always uses an IP from `10.0.1.1`
to `10.0.3.3` and listens on port `8080`). Add a rule for the external IP `1.1.1.1`,
external port `1111`, and internal host `1`. Then, try to read the rule.
You should see an output similar to

    Ingress rules:
    (1.1.1.1:1111) -> (10.0.1.1:8080)
    Egress rules:
    (10.0.1.1:8080) -> (1.1.1.1:1111)

Let us now test that the rule behaves correctly. For this example, we will not
use the standard `ping` command to send packets, since it does not allow
us to set the values of packet header fields (such as TCP ports). Instead,
run the following command in mininet:

    xterm h1 h4

This will open a small console window for hosts `h1` and `h4`.
In the window for `h1`, run:

    ./receive.py

This will start a program on host `h1` which listens for TCP packets on port 8080.

You can now use the `send.py` program in the window for `h4` to send packets to one
of the other hosts. Try to run:

    ./send.py 1.1.1.1 1111 "hello"

The message should then appear in the `h1` window as a message from `10.0.4.4:8080`.

Finally, try to swap such that you listen with `receive.py` on `h4` and send from `h1`.
In the window for `h1`, run:

    ./send.py 10.0.4.4 8080 "hello again"

The window for `h4` should display the message as received from the "translated" address
of `h1`, namely `1.1.1.1:1111`.

### Load balancing (Section 8.3 in the companion paper)

The example can be found in `$ROOT/P4R-Type/examples/src/main/scala/loadbalancer.scala`
and can be executed by running (on the host machine, from inside the directory
`$ROOT/P4R-Type/`):

    sbt "examples/runMain loadbalancer"

__Effect__: The program manages load balancing of packets going through `s1` to `h4`.
It will first establish full connectivity between hosts. Then, in intervals of 5
seconds (for a total of one minute), it will read from the CounterEntry at `s1` and
modify its forwarding rules accordingly such that packets are evenly sent between
`s2`, `s3` and `s4`.

For this example, first start mininet (in the VM) by running:

    make test_lb

To send packets, use:

    h1 ping h4

To see how the packets are distributed (and counted), run the `ping` command _after_
running the control program. Observe how the control program counts the outgoing
packets and updates the outgoing port accordingly.

## Creating a new scenario

We now provide an small example to demonstrate how updating/extending a P4 configuration can cause
existing P4Runtime programs to "go out of sync".  Our P4R-Type API can detect these
situations and produce type errors, thus preventing incorrect P4Runtime programs from compiling and running.

  1. In the VM, replace the contents of file `config1.p4` (in the home directory of the user P4RType),
     with the contents of `config1_new.p4` (also in the same directory). The files are almost identical,
     except that the `dstAddr` field in the IPv4 header is renamed to `dstIP`.
  2. In the VM, recompile the P4 file and P4Info file by running `make clean` followed by `make build`.
     This will generate a new P4Info file in `build/config1.p4.p4info.json`.
  3. Copy the contents of the new P4Info file onto a file in your host machine, such as
     `$ROOT/P4R-Type/examples/src/main/scala/config1_new.p4info.json`.
  4. On the host machine, generate new types from the updated P4info file. If you use the file name above,
     the command is:

         sbt "runMain typegen.parseP4info examples/src/main/scala/config1_new.p4info.json config1_new"

  5. On the host machine, create a new Scala file `$ROOT/P4R-Type/examples/src/main/scala/config1_new.scala`,
     and copy&paste there the Scala code produced by the command at point 4.
  6. On the host machine, edit the file `$ROOT/P4R-Type/examples/src/main/scala/forward_c1.scala` by
     replacing the package name `config1` in lines 6 and 7 with `config1_new`.
  7. On the host machine, try to compile the modified program above by running:

         sbt compile

     The compilation should fail, reporting an error around line 11 and 27: this reflects the fact that the
     code does not match the updated P4 configuration (it is referring to a field called `dstAddr`,
     but the field is now called `dstIP`).
  8. Fix the program by replacing all occurrences of `hdr.ipv4.dstAddr` with `hdr.ipv4.dstIP`
     (line __11__ and __27__). The program should now compile.
  9. To test that the program works as intended, follow the same steps as for running the
     __Simple IPv4 table update__ example (remember to also start the network in the VM).

If you are familiar with the P4 language, you can follow the steps above to try more experiments: you
can apply other changes to the file `config1.p4` (e.g. rename actions, change their parameter types,
modify the associations between tables and actions...) and observe how the changes are reflected in
the types generated by P4R-Type, and how a program using the P4R-Type API must be updated in order to
type-check and compile after such changes.

## Related work, and what else the types could check

### Where this sits

The companion paper is [P4R-Type: A Verified API for P4 Control Plane Programs](https://dl.acm.org/doi/10.1145/3622866)
(Larsen, Guanciale, Haller & Scalas, OOPSLA 2023; technical report
[arXiv:2309.03566](https://arxiv.org/abs/2309.03566), 82pp). It remains the only
formalisation of P4Runtime *control plane* programs. Nothing has superseded it,
and no typed P4Runtime wrapper exists for Rust, Go or Python — p4runtime-shell,
ONOS/Stratum and PINS are all dynamically typed. Upstream
([JensKanstrupLarsen/P4R-Type](https://github.com/JensKanstrupLarsen/P4R-Type))
is the OOPSLA artifact and is not maintained; this fork is the live branch.

Two neighbouring lines of work:

  * **[Π4](https://arxiv.org/pdf/2206.03457)** (Eichholz, Campbell, Krebs, Foster
    & Mezini, POPL 2022) — dependent types with SMT-decidable refinements for the
    P4 **data plane**. Complementary rather than competing: Π4 would type
    `quackmpp.p4`, P4R-Type types the controller that drives it. Nothing connects
    the two, even though both consume the same P4 program.
  * **[SafeP4](https://arxiv.org/pdf/1906.07223)** — header-validity types, the
    weaker predecessor Π4 cites as unable to reason about runtime values.

The original authors have moved on to
**[NEST: Network Enforced Session Types](https://drops.dagstuhl.de/entities/document/10.4230/LIPIcs.ECOOP.2026.17)**
(ECOOP 2026) — protocol monitors synthesised into the data plane. A different
problem; do not expect upstream P4R-Type development.

### Yes, this is already dependent typing

Scala 3's [dependent function types](https://docs.scala-lang.org/scala3/book/types-dependent-function.html)
are sometimes proposed as a way to make this API safer. They are already in use.
`TableEntry.apply` (`src/main/scala/api/p4rtype.scala`) is a **dependent method
type**:

```scala
def apply[TM[_], TA[_], TP[_]](
  table: String, matches: TM[table.type], action: TA[table.type],
  params: TP[action.type], priority: Int)
```

The type of `matches` depends on the *value* of `table`, through its singleton
type `table.type`, resolved by the generated match types. That dependency is what
makes a renamed P4 field a compile error. The documentation page above describes
dependent *function* types — the first-class `(k: Key) => Option[k.Value]` value
form — which generalise this to lambdas. That generalisation buys composability
this API does not currently need.

The productive question is therefore not "can we use dependent types" but **which
p4info facts does `typegen` throw away**.

### p4info facts currently discarded

`typegen` reads `matchType` and never reads `bitwidth`. From
`src/test/resources/quackmpp_exchange.p4info.json`:

    "bitwidth": 16,  "matchType": "EXACT"   # meta.quack.bucket
    "bitwidth": 32                          # worker_id
    "bitwidth": 9                           # port

Three candidate extensions, highest value first:

  1. **Bitwidth.** The hole is over-large *values*, not over-long encodings: an
     `Exact(bytes(0, 0, 7))` on a `bit<16>` field is canonicalised to `bytes(7)`
     on the way out and the switch never sees it. But `port` is `bit<9>` —
     maximum 511 — and nothing stops a controller passing 600 (`bytes(2, 88)`),
     which canonicalising cannot shrink. Closing this means emitting a width
     match type and comparing `canonical(...)`'s length **and the significant
     bits of its top byte** against the bitwidth — length alone is not enough,
     since `bit<9>`'s largest legal value 511 is `bytes(1, 255)` and the illegal
     600 is `bytes(2, 88)`, both two bytes long. For
     QuackMPP specifically, a bucket id that overflows `bit<16>` is a silent
     misroute rather than a crash, which makes this the one worth doing.
  2. **Priority.** The P4Runtime spec requires priority 0 on exact-only tables and
     nonzero on tables with a ternary/range/optional field. The p4info knows the
     match kinds; `priority: Int` is currently unconstrained. It could be the
     singleton type `0` for `QuackMPP.exchange` — which would break
     `QuackMppTypegenSuite`, whose exchange entry uses `priority = 1`
     deliberately (see [ARCHITECTURE.md](ARCHITECTURE.md) §7 gap 6).
  3. **Counters, meters, digests.** `CounterEntry(counter_id: Int, ...)` is a bare
     integer with no type safety at all — precisely what the paper eliminated for
     tables, left undone for every other entity kind.

### Two caveats before building on this

`fromProto` in the generated code is a sequence of `asInstanceOf` casts. The
guarantee is genuinely a **write-path** guarantee; the read path is dynamically
typed and merely does not crash. Worth knowing before basing state reconciliation
on it.

And there is a real cost to encoding more. Renaming a match field reports
*"match type could not be fully reduced"* rather than naming the field — which is
why `QuackMppTypegenSuite` asserts only that compilation fails, and pairs the
negative tests with a control test. Every additional fact pushed into match types
makes those messages worse. Bitwidth is worth that tax; much beyond it needs a
plan for diagnostics first.
