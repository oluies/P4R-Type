val scala3Version = "3.8.4"

val grpcVersion   = "1.82.2"
val zioVersion    = "2.1.26"
val json4sVersion = "1.0.0-alpha.1"
val munitVersion  = "1.3.4"

lazy val root = project
  .in(file("."))
  .settings(
    name         := "p4rt-scala",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,

    // scalapb-json4s only publishes 1.0.0-alpha.1 on the 1.0 line and pins
    // scalapb-runtime 1.0.0-alpha.1, while sbt 2 forces scalapb >= 1.0.0-alpha.5.
    // Scoped to that one artifact on purpose: a blanket `evictionErrorLevel :=
    // Level.Warn` would also silence every future binary-incompatible eviction
    // (grpc, zio, protobuf-java) that Scala Steward bumps into the build.
    libraryDependencySchemes +=
      "com.thesamet.scalapb" %% "scalapb-runtime" % VersionScheme.Always,

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

    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime"      % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "com.thesamet.scalapb" %% "scalapb-json4s"       % json4sVersion,
      "io.grpc"               % "grpc-netty"           % grpcVersion,
      "dev.zio"              %% "zio"                  % zioVersion,
      "dev.zio"              %% "zio-streams"          % zioVersion,
      "org.scalameta"        %% "munit"                % munitVersion % Test
    )
  )
