addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.1.0-RC2")

// ScalaPB 1.0.0-alpha.x is the only line whose codegen chain (protoc-gen_3 /
// protoc-cache-coursier_3 -> protoc-bridge_3 0.9.10) matches sbt-protoc's sbt2
// build. ScalaPB 0.11.x pulls protoc-bridge_2.13 and fails to load under sbt 2.
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "1.0.0-alpha.6"
