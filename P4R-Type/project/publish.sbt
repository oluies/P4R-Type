// Signing, for Maven Central. `publishSigned` produces the `.asc` files Central
// requires. Verified to have an sbt 2 build: com.github.sbt:sbt-pgp_sbt2_3:2.3.1.
//
// The usual companion, sbt-sonatype, is deliberately absent: it is deprecated
// and has no sbt 2 build, and sbt 2.0.3 does not yet ship the native
// `sonaUpload`/`localStaging` commands its replacement recipe uses. The upload
// step is therefore out-of-band — see PUBLISHING.md.
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.1")
