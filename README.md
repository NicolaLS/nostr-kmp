# nostr-kmp

## Publishing

### GitHub setup

Configure these repository secrets in GitHub under
`Settings -> Secrets and variables -> Actions -> Repository secrets`:

- `MAVEN_CENTRAL_USERNAME`: Maven Central Portal token username.
- `MAVEN_CENTRAL_PASSWORD`: Maven Central Portal token password.

Release publishing also needs signing secrets:

- `SIGNING_KEY_ID`
- `SIGNING_PASSWORD`
- `GPG_KEY_CONTENTS`

Snapshot publishing does not need signing secrets.

Before publishing snapshots, enable snapshots for the `io.github.nicolals`
namespace in the Maven Central Portal. Sonatype serves snapshots from:

```text
https://central.sonatype.com/repository/maven-snapshots/
```

### Publish a snapshot

Keep the root project version in `build.gradle.kts` as a `-SNAPSHOT` version,
for example:

```kotlin
version = "0.3.1-SNAPSHOT"
```

Then run the manual GitHub workflow:

1. Open `Actions` in GitHub.
2. Select `Publish Snapshot`.
3. Click `Run workflow`.
4. Select the branch to publish, usually `main`.
5. Click `Run workflow`.

Verify a snapshot upload with module metadata, for example:

```sh
curl -fsSL https://central.sonatype.com/repository/maven-snapshots/io/github/nicolals/nostr-core/0.3.1-SNAPSHOT/maven-metadata.xml
```

Consumers need the snapshot repository explicitly:

```kotlin
repositories {
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
    mavenCentral()
}
```

### Publish a release

Set the root project version to a non-snapshot version and publish with signing
enabled. Locally, set `NOSTR_KMP_ENABLE_SIGNING=1`, reload direnv, then run:

```sh
./gradlew publishToMavenCentral --parallel
```
