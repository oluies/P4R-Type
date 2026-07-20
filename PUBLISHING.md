# Publishing to Maven Central

This library publishes to **Maven Central** so consumers (QuackMPP) resolve it
with no authentication:

```scala
// Mill 0.12+/1.x
mvn"io.github.oluies::p4rt-scala:0.1.0"
// Mill 0.11 and earlier
ivy"io.github.oluies::p4rt-scala:0.1.0"
```

## Why the setup is non-standard

The usual sbt path — `sbt-sonatype` + `sbt-ci-release` — **does not work on this
build**, and the reasons are worth recording so nobody re-discovers them:

* `sbt-sonatype` is **deprecated** (Sonatype retired the legacy OSSRH API it
  drove) and has **no sbt 2 build**. Confirmed: `org/xerial/sbt/sbt-sonatype`
  publishes only `_2.12_1.0` (sbt 1), not `_sbt2_3`.
* `sbt-ci-release` likewise has no sbt 2 build.
* The sbt 2.x Central recipe uses native `sonaUpload` / `localStaging` commands,
  but those are **not in sbt 2.0.3** — which is the *latest* sbt (there is no
  newer 2.x to move to). `help sonaUpload` finds nothing.

So the working path on this toolchain is: **sbt-pgp signs, and the bundle is
uploaded to the Central Portal out-of-band** (web UI or Publisher API). The
build (`build.sbt`, `project/publish.sbt`) is configured for exactly this and is
verified to load, sign, and emit a Central-valid POM. The only step that cannot
be verified without live credentials is the upload itself — that happens on your
first real release.

## One-time setup (you — involves credentials, so not done for you)

1. **Central Portal account + namespace.** Register at
   <https://central.sonatype.com>, then add and verify the namespace
   `io.github.oluies`. For an `io.github.<user>` namespace the Portal verifies
   you by having you create a throwaway public GitHub repo it names; follow its
   on-screen instructions. (The `io.github.oluies` groupId in `build.sbt` was
   chosen for exactly this — it is verifiable through your GitHub account, unlike
   a vanity domain which would need DNS.)

2. **A GPG key**, because Central requires signed artifacts:

   ```bash
   gpg --gen-key                     # note the key id and passphrase
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>   # Central checks here
   ```

   Point sbt-pgp at it — the simplest is a `gpg` agent on PATH; sbt-pgp uses it
   by default. (For unattended CI you would export `PGP_SECRET` and
   `PGP_PASSPHRASE`; not needed for a manual first release.)

3. **A Central Portal user token.** In the Portal account settings, generate a
   token (a username/password pair). You will paste it into the upload step, or
   set `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` for the Publisher API.

**Do not send any of these to the assistant.** The release is run by you; the
credentials never need to pass through this session.

## Cutting a release

1. **Set a real version.** Central rejects `-SNAPSHOT` on the release repository.
   In `build.sbt` change `version := "0.1.0-SNAPSHOT"` to `version := "0.1.0"`.
   (Keep `-SNAPSHOT` on `main` between releases; only the release commit is
   non-snapshot. When cutting the next one, bump to `0.1.1` / `0.2.0`.)

2. **Sign into a local staging tree** — no network, nothing leaves the machine:

   ```bash
   cd P4R-Type
   sbt clean publishSigned
   # -> target/central-staging/io/github/oluies/p4rt-scala_3/0.1.0/
   #    .jar .pom -sources.jar -javadoc.jar, each with .asc .md5 .sha1
   ```

   Inspect that tree once. Every artifact must have its `.asc` (signature) and
   the `.md5` / `.sha1` checksums, or Central will reject the bundle.

3. **Bundle and upload.** Zip the Maven tree so the zip's top entries are
   `io/github/oluies/...`:

   ```bash
   cd P4R-Type/target/central-staging
   zip -r ../p4rt-scala-0.1.0-bundle.zip io
   ```

   Then either:
   * **Web UI (recommended first time):** Central Portal → *Publish* → upload the
     zip. It validates, then you click *Publish*. You see any rejection reason
     before anything goes public.
   * **Publisher API:** `POST` the zip to
     `https://central.sonatype.com/api/v1/publisher/upload` with a
     `Authorization: Bearer <base64(user:token)>` header. (The endpoint is live;
     it answers `401` unauthenticated.)

4. **Tag the release** so the git history matches the published version:

   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```

5. **Wait for the sync.** A first-ever artifact takes ~15–30 min to appear on
   `repo1.maven.org`. After that, QuackMPP resolves it with no auth, and the
   `.p4rt-version` marker / build-from-source job on the QuackMPP side can be
   deleted.

## Automating it later

Once the first manual release proves the setup, a `release.yml` workflow can run
`publishSigned` + the Publisher-API upload on a `v*` tag, using
`SONATYPE_USERNAME` / `SONATYPE_PASSWORD` / `PGP_SECRET` / `PGP_PASSPHRASE` repo
secrets. It is deliberately **not** added now: a CI upload cannot be tested
without the live credentials, and shipping an unrunnable workflow is the kind of
looks-right-doesn't-work artifact this project has spent a lot of effort
avoiding. Do the first release by hand, then automate against a known-good flow.
