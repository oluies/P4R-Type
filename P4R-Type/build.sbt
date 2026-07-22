val scala3Version = "3.8.4"

val grpcVersion   = "1.82.2"
val protobufVersion = "4.35.1"   // must match what scalapb-runtime pulls
val munitVersion  = "1.3.4"

// --- Release signing, driven entirely by environment ------------------------
// Set only in the release workflow (from repo secrets). Unset everywhere else,
// which is what makes an interactive `publishSigned` still prompt normally
// through pinentry: `None` means sbt-pgp omits --passphrase and gpg asks.
//
// With a passphrase present sbt-pgp's CommandLineGpgSigner runs
// `gpg --batch --pinentry-mode loopback --passphrase <pw> --detach-sign`,
// which is the only form that signs on a runner with no TTY — a plain
// --use-agent invocation there dies with "Inappropriate ioctl for device".
ThisBuild / pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray)
ThisBuild / pgpSigningKey := sys.env.get("PGP_KEY_ID")

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
    // The release workflow derives this from the pushed tag (v0.1.0 ->
    // RELEASE_VERSION=0.1.0), so cutting a release never edits a tracked file
    // and the published version cannot disagree with the tag it came from.
    // Unset, the build stays a snapshot — which is what every non-release
    // build, including all of CI, sees.
    version      := sys.env.getOrElse("RELEASE_VERSION", "0.1.1-SNAPSHOT"),
    scalaVersion := scala3Version,

    // --- Maven Central (Central Portal) publishing metadata -----------------
    // Central rejects a POM missing any of these. `io.github.oluies` is a
    // namespace the Central Portal verifies through the matching GitHub account,
    // which is why the organization above was chosen that way.
    //
    // NOTE on mechanics: sbt-sonatype is deprecated and has no sbt 2 build, and
    // the `sonaUpload`/`localStaging` commands in the sbt 2.x Central recipe are
    // not present in sbt 2.0.3 (the latest sbt). So the release path here is
    // sbt-pgp `publishSigned` into `target/central-staging`, then a bundle upload
    // to the Central Portal — see PUBLISHING.md. This block is only the metadata;
    // it changes nothing about compile/test/CI.
    description  := "A verified, type-safe P4Runtime control-plane API for Scala 3: " +
      "types generated from a p4info make a renamed or removed P4 match field a compile error.",
    homepage     := Some(url("https://github.com/oluies/P4R-Type")),
    licenses     := List("MIT" -> url("https://opensource.org/licenses/MIT")),
    developers   := List(
      Developer("oluies", "Örjan Lundberg", "orjan@lundberg.me", url("https://github.com/oluies"))
    ),
    scmInfo := Some(ScmInfo(
      url("https://github.com/oluies/P4R-Type"),
      "scm:git:https://github.com/oluies/P4R-Type.git"
    )),
    // Lets consumers' resolvers reason about binary compatibility across 0.x.
    versionScheme := Some("early-semver"),
    publishMavenStyle := true,
    // publishSigned lands here in Maven layout; the release bundle is this tree
    // zipped. A real Central endpoint is never configured in the build — upload
    // is out-of-band (Portal web UI or its Publisher API), so a stray `publish`
    // cannot reach the internet.
    publishTo := Some(Resolver.file("central-staging", target.value / "central-staging")),

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
