val scala3Version = "3.8.4"

val grpcVersion   = "1.82.2"
val protobufVersion = "4.35.0"   // must match what scalapb-runtime pulls
val munitVersion  = "1.3.4"

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
    // System.getProperty("user.dir"), which sbt sets to the project base
    // directory only for *unforked* tests. Pin fork off so that stays true —
    // this is already the default, but the test depends on it, so it is stated
    // rather than assumed. (Putting the test source dir on the resource
    // classpath instead does not work: sbt excludes *.scala from resources.)
    Test / fork := false,

    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime"      % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "com.google.protobuf"   % "protobuf-java-util"   % protobufVersion,
      "io.grpc"               % "grpc-netty"           % grpcVersion,
      "org.scalameta"        %% "munit"                % munitVersion % Test
    )
  )
