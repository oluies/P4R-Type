val scala3Version = "3.8.4"

val grpcVersion   = "1.82.2"
val protobufVersion = "4.35.0"   // must match what scalapb-runtime pulls
val munitVersion  = "1.3.4"

// The library. This is the only thing published, and it must contain only the
// P4R-Type API + generated P4Runtime bindings + typegen — see `examples` below.
lazy val root = project
  .in(file("."))
  .settings(
    // Without an explicit organization, sbt derives the groupId from the project
    // name, producing the meaningless coordinate `p4rt-scala %% p4rt-scala`.
    // Downstream consumers (QuackMPP's Mill build) need a real one.
    organization := "io.github.oluies",
    name         := "p4rt-scala",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,

    // parseP4info calls System.exit on its error paths, and sbt's trapExit needs
    // a SecurityManager, which JDK 24+ refuses to install — so unforked, that
    // exit runs in sbt's own JVM. In practice sbt 2 survives it (it reports
    // "nonzero exit code returned from runner" and carries on), but a CLI that
    // exits should not be sharing a JVM with the build tool on trust.
    Compile / run / fork := true,

    // Silence warnings due to ScalaPB-generated code
    scalacOptions ++= Seq(
      "-Wconf:cat=deprecation&msg=Marked as deprecated in proto file:silent"
    ),

    // The P4Runtime + google .proto sources live in src/protobuf, not sbt-protoc's
    // default of src/main/protobuf. Without this, PB.targets silently generates nothing.
    Compile / PB.protoSources := Seq(baseDirectory.value / "src" / "protobuf"),

    Compile / PB.targets := Seq(
      scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb"
    ),

    // QuackMppTypegenSuite's drift check reads the committed fixture as text via
    // System.getProperty("user.dir"), so that has to be the project base
    // directory. Forking is what guarantees it: sbt builds fork options with
    // workingDirectory = baseDirectory, whereas an *unforked* test runs in sbt's
    // own JVM and simply inherits user.dir from wherever sbt was launched — which
    // only looks correct because you usually launch sbt from here. Under a tool
    // that starts the test JVM elsewhere (Metals/Bloop at the workspace root)
    // unforked would break. Verified: forked also still inherits the ambient
    // environment, which Bmv2WireSuite's P4RT_BMV2 needs.
    // (Putting the test source dir on the resource classpath instead does not
    // work: sbt excludes *.scala from resources.)
    Test / fork := true,

    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime"      % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "com.google.protobuf"   % "protobuf-java-util"   % protobufVersion,
      "io.grpc"               % "grpc-netty"           % grpcVersion,
      "org.scalameta"        %% "munit"                % munitVersion % Test
    )
  )

/** The examples, as a separate project that is never published.
  *
  * They used to live in `src/main/scala/examples/` and therefore shipped inside
  * the library jar: `bridge`, `firewall`, `forward_c1`, `forward_c2`,
  * `loadbalancer`, `p4rtypeTest` and `router` declare no package, so they landed
  * in the **default package** at the jar root (35 class/tasty entries), and the
  * `config*` packages are typegen output for a specific p4info — someone else's
  * types, shipped to every consumer. Both are collision hazards for anything
  * embedding this library (QuackMPP).
  *
  * They stay compiled — CI runs `examples/compile`, so breakage is still caught —
  * but `publish / skip` keeps them out of the artifact. They cannot simply move
  * to test scope: they are documented entry points in README.md and need the
  * mininet VM to run.
  *
  * Deliberately NOT aggregated by `root`: `root.aggregate(examples)` combined
  * with `examples.dependsOn(root)` hangs sbt 2 during project loading.
  */
lazy val examples = project
  .in(file("examples"))
  .dependsOn(root)
  .settings(
    name           := "p4rt-scala-examples",
    scalaVersion   := scala3Version,
    publish / skip := true

    // Deliberately NOT `Compile / run / fork := true`, unlike root. sbt does not
    // forward stdin to a forked process (`run / connectInput` defaults to
    // false), and `router` drives its entire CLI off scala.io.StdIn.readLine —
    // forked, that reads EOF, gets null, and loops forever printing
    // "Invalid action." The fork on root exists because parseP4info calls
    // System.exit; no example does, and none reads a cwd-relative file, so a
    // fork here would buy nothing and cost the CLI. CI only compiles the
    // examples (they need the VM), so nothing would have caught it.
  )
