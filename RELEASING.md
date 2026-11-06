# Releasing

How a maintainer publishes a version. Every module goes to Maven Central under `org.adelheid`, named after its
folder, each for the JVM, JS and Wasm targets. All modules share one version.

## One-time setup

The credentials stay outside the repository, in `~/.gradle/gradle.properties` or as environment variables
(`ORG_GRADLE_PROJECT_<name>`). Never commit them: the build stops when this repository's own `gradle.properties` or
`local.properties` names one.

| Property | What it is |
| --- | --- |
| `mavenCentralUsername`, `mavenCentralPassword` | A user token from the [Central Portal](https://central.sonatype.com/) (Account, Generate User Token), for an account that owns the `org.adelheid` namespace. |
| `signing.keyId`, `signing.password`, `signing.secretKeyRingFile` | The PGP key that signs the artifacts: the last eight characters of its ID, its passphrase, and a file holding the secret key (`gpg --export-secret-keys -o secring.gpg <key id>`), kept outside this repository. An ASCII-armoured key in `signingInMemoryKey`, `signingInMemoryKeyId` and `signingInMemoryKeyPassword` works as well. The public key must be on a key server Maven Central reads, such as `keyserver.ubuntu.com`. |

The build signs publications only when a signing key is configured, so that anyone can publish to a local repository.
Maven Central rejects unsigned artifacts.

## Steps

1. Make sure `main` builds: `./gradlew clean build`. That runs the JVM, Node and browser tests; the browser tests need
   Chrome.
2. If `native/png/src` changed, rebuild the package with `native/png/build.ps1` and commit `native/png/pkg`.
3. In `gradle.properties`, set `version` to the release version, and set the versions in the README to it too.
4. Stage the release locally and look at it before anything is uploaded. Run this as its own command, not together
   with the build above:

   ```sh
   ./gradlew clean publishAllPublicationsToStagingRepository
   ```

   `build/staging-repo` then holds what Maven Central would receive: four artifacts for each module (the common one
   and one per target), each with its POM, its Gradle module file, a jar or klib, sources and Javadoc jars, and a
   signature beside every file. Read one POM, and check that no artifact names a folder of this machine. This prints
   nothing when they are clean:

   ```sh
   find build/staging-repo -type f \( -name "*.jar" -o -name "*.klib" \) | while read -r f; do
     unzip -p "$f" | grep -a -q -E "[A-Za-z]:[\\\\/](Users|GitHub)|/Users/|/home/" && echo "$f"
   done
   ```

5. Commit, then tag the commit `v<version>`.
6. Publish to Maven Central:

   ```sh
   ./gradlew publishToMavenCentral
   ```

   Check the deployment on the Central Portal, under Deployments, and press Publish. It takes a few minutes to reach
   Maven Central. A published version is permanent: it can be neither changed nor removed.
7. Push the commit and the tag.
8. Set `version` to the next snapshot, and commit.

## What a JS or Wasm consumer of the map converter needs

The JS and Wasm artifacts of `hondius-akm-converter` import the npm module `hondius-png` from `native/png/pkg`. Maven
does not carry it, so those consumers declare it from a checkout of this repository, as the README shows. Every other
artifact is complete on its own.
