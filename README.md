# Objective

Provide a [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) *Nostr* SDK that developers can adopt
with **confidence**. Layers of abstraction are used by the SDK itself, but can also be used by developers to "branch
off" for a more tailored solution. Designed to **allow customization** while reducing duplicated efforts and bugs to a
minimum.

# Modules

| Module | Artifact Id | Purpose |
| ------ | ----------- | ------- |
| `nostr-core` | `nostr-core` | Protocol models, session engine/reducer, shared utilities. |
| `nostr-codec-kotlinx-serialization` | `nostr-codec-kotlinx-serialization` | Kotlinx-based encoder/decoder for wire messages. |
| `nostr-runtime-coroutines` | `nostr-runtime-coroutines` | Coroutine runtime, connection telemetry, interceptors, lifecycle helpers. |
| `nostr-transport-ktor` | `nostr-transport-ktor` | Ktor-based transport implementation (`RelayConnection`). |
| `nostr-crypto` | `nostr-crypto` | ACINQ secp256k1 bindings and helpers for signing/encryption. |

All modules share the `io.github.nicolals` group and a single SDK version.

# Installation

Add the repository hosting the artifacts (Maven Central or your local Maven cache), then declare the modules you need. Example using Kotlin DSL:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal() // keep if you publish snapshots locally
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.nicolals:nostr-core:<version>")
    implementation("io.github.nicolals:nostr-runtime-coroutines:<version>")
    implementation("io.github.nicolals:nostr-transport-ktor:<version>")
    implementation("io.github.nicolals:nostr-codec-kotlinx-serialization:<version>")
    implementation("io.github.nicolals:nostr-crypto:<version>")
}
```

Replace `<version>` with the published SDK version (for example `0.1.0` or `0.1.0-SNAPSHOT`).

# Publishing

The build is configured to use a single set of coordinates defined in `gradle.properties`:

```
GROUP=io.github.nicolals
VERSION_NAME=0.1.0-SNAPSHOT
```

## Publish to Maven Local

Use this workflow when iterating locally or consuming the SDK from another project on the same machine:

```bash
./gradlew clean publishToMavenLocal
```

Artifacts will appear under `~/.m2/repository/io/github/nicolals/…`. In your consuming project, ensure `mavenLocal()` is listed in the repository block.

## Publish to Maven Central

1. Update `VERSION_NAME` in `gradle.properties` (e.g. `0.1.0`).
2. Configure your Sonatype credentials and PGP signing keys (environment variables or `gradle.properties` entries such as `signing.keyId`, `signing.password`, `signing.secretKeyRingFile`, `mavenCentralUsername`, `mavenCentralPassword`).
3. Run:

   ```bash
   ./gradlew clean publishAllPublicationsToMavenCentral
   ```

4. Close and release the staging repository via Sonatype’s UI or with the Vanniktech helper task:

   ```bash
   ./gradlew closeAndReleaseMavenCentral
   ```

## Versioning Strategy

- Maintain a **single version** for the entire SDK (`VERSION_NAME`). Bump it whenever any module introduces a change.
- Use semantic versioning across the monorepo (MAJOR for breaking API changes, MINOR for backward-compatible features, PATCH for fixes).
- For preview or unreleased builds, append classifiers such as `-SNAPSHOT` or `-alpha01`.
- After a release, increment `VERSION_NAME` immediately to the next snapshot to keep local builds distinct (e.g. move from `0.1.0` to `0.1.1-SNAPSHOT`).

Because every module shares the same version, downstream consumers can mix and match components without worrying about mismatched APIs.
