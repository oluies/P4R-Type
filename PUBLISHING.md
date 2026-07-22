# Publishing to Maven Central

This library publishes to **Maven Central** so consumers (QuackMPP) resolve it
with no authentication:

```scala
// Mill 0.12+/1.x
mvn"io.github.oluies::p4rt-scala:0.1.0"
// Mill 0.11 and earlier
ivy"io.github.oluies::p4rt-scala:0.1.0"
```

Releases are cut by **CI, on a tag** — `.github/workflows/release.yml`. Nothing
about a release is done by hand on a laptop, and no credential ever sits in a
shell history: the signing key and the Portal token are repo secrets, the
artifact is built from a clean checkout, and the published version cannot
disagree with the tag because it *is* the tag (`v0.1.0` → `RELEASE_VERSION=0.1.0`,
consumed by `version` in `build.sbt`). Between releases `main` stays
`0.1.0-SNAPSHOT`; no release ever edits a tracked file.

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

So the working path is: **sbt-pgp signs into a local staging tree, and the
workflow uploads that tree as a bundle to the Central Portal Publisher API.**

## One-time setup (yours — it involves credentials)

**Never send any of these to the assistant.** Every command below is one you run.

### 1. Central Portal account + namespace

Register at <https://central.sonatype.com> and verify the namespace
`io.github.oluies`. The Portal verifies an `io.github.<user>` namespace by having
you create a throwaway public GitHub repo with the name it gives you. (The
`io.github.oluies` groupId in `build.sbt` was chosen for exactly this — it is
verifiable through your GitHub account, unlike a vanity domain, which needs DNS.)

*Status: done and verified.*

### 2. A GPG key, published to a keyserver

Central requires signed artifacts and checks the public key on a keyserver:

```bash
gpg --full-generate-key                                  # use a passphrase
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

Use **keyserver.ubuntu.com**. `keys.openpgp.org` is not sufficient on its own: it
strips user IDs from keys whose email is unconfirmed, and Central queries the
Ubuntu keyserver. Verify it actually landed:

```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys <KEY_ID>
```

*Status: key `A04D7C30CE634B97` confirmed present on keyserver.ubuntu.com.*

### 3. Four repo secrets

```bash
# The secret key, armored and base64'd — this is what CI imports.
gpg --armor --export-secret-keys <KEY_ID> | base64 | tr -d '\n' \
  | gh secret set PGP_SECRET

gh secret set PGP_PASSPHRASE            # prompts; paste the key's passphrase
gh secret set CENTRAL_TOKEN_USERNAME    # from Portal -> Account -> Generate User Token
gh secret set CENTRAL_TOKEN_PASSWORD
```

The key **fingerprint is not a secret and is not stored** — the workflow derives
it from the imported key and echoes it, so signing with the wrong key is visible
in the log.

> If a Portal token is ever exposed (pasted into a chat, a log, a screenshot),
> revoke it in the Portal and generate a new one. Tokens are cheap; a compromised
> one can publish under your namespace permanently, because Central releases are
> immutable.

## Cutting a release

### 1. Dry run first

Actions → **Release** → *Run workflow*, leaving `dry_run` checked. This compiles,
runs the full suite, signs, verifies every signature and checksum, and attaches
the bundle to the run — but uploads nothing. It exercises every step except the
upload.

### 2. Tag

```bash
git tag v0.1.0 && git push origin v0.1.0
```

That triggers the real release. The workflow refuses any version containing
`SNAPSHOT`, and runs `testFull` *before* signing — an artifact whose own tests
fail must never reach Central, because a bad `0.1.0` can only be superseded,
never withdrawn.

### 3. Press Publish

The default `publishingType` is `USER_MANAGED`, so the deployment stops at
*validated* and waits for you at
<https://central.sonatype.com/publishing/deployments>. Review it, then publish.
Switch the input to `AUTOMATIC` once you trust the pipeline.

### 4. Wait for the sync

A first-ever artifact takes ~15–30 min to appear on `repo1.maven.org`. After
that, QuackMPP resolves it with no auth, and the `.p4rt-version` marker /
build-from-source job on the QuackMPP side can be deleted.

### 5. Bump

Nothing to do — `main` is already `0.1.0-SNAPSHOT`. When cutting `0.1.1` or
`0.2.0`, bump the fallback in `build.sbt` if you want local snapshot builds to
carry the newer number; the released version comes from the tag either way.

## What has actually been verified, and what has not

Publishing is the one area of this repo where "it looks right" is cheapest and
worth least, so: the signing path was exercised end to end against a **throwaway
GPG key in an isolated keyring**, not merely reasoned about. Confirmed:

* `publishSigned` signs unattended with no TTY, given `PGP_PASSPHRASE`. sbt-pgp's
  `CommandLineGpgSigner` then runs `gpg --batch --pinentry-mode loopback
  --passphrase …`; without a passphrase set it uses `--use-agent` and dies on a
  runner with `gpg: signing failed: Inappropriate ioctl for device`.
* The staged tree contains `.jar`, `-sources.jar`, `-javadoc.jar` and `.pom`,
  each with `.asc`, `.md5` and `.sha1` — the full set Central requires.
* All four signatures **cryptographically verify**, not just exist.
* The verification step *fails* when it should: tampering with a jar and
  emptying a `.asc` were both caught, and it passed again once restored.
* The POM carries every field Central demands and no `SNAPSHOT` dependency.
* The published jar has nothing in the default package and no `examples`.

**Not verified:** the upload itself. It needs live credentials, so it runs for
the first time on your first real release — which is what the dry run and the
`USER_MANAGED` review step exist to de-risk.

**A trap worth naming:** the staging tree is **not** at `target/central-staging`.
sbt 2 resolves `target.value` per project, so it lands at
`target/out/jvm/scala-<scalaVersion>/p4rt-scala/central-staging` — a path that
moves with the Scala version. This document previously gave the wrong one. The
workflow therefore *finds* it (`find target -type d -name central-staging`)
rather than hardcoding it.

## Releasing from a laptop instead

Only if CI is unavailable. `gpg` must be able to prompt, so run it in a real
terminal:

```bash
cd P4R-Type
RELEASE_VERSION=0.1.0 sbt "clean; publishSigned"
STAGE=$(find target -type d -name central-staging -print -quit)
(cd "$STAGE" && zip -qr /tmp/p4rt-scala-0.1.0-bundle.zip io)
```

Then upload `/tmp/p4rt-scala-0.1.0-bundle.zip` through the Portal web UI. Tag the
commit afterwards so history matches what was published.
