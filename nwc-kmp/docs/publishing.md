# Publishing

`nwc-kmp` is published by the root `nostr-kmp` build. Its Maven coordinates remain
`io.github.nicolals:nwc-kmp`, and its version comes from the root
[`build.gradle.kts`](../../build.gradle.kts), currently `0.3.3-SNAPSHOT`.

Follow the shared [publishing guide](../../docs/publishing.md) for repository
secrets, signing, snapshots, and releases. The root `Publish Snapshot` workflow
includes `nwc-kmp` along with the SDK modules.

Run local verification from the `nostr-kmp` repository root:

```sh
./gradlew :nwc-kmp:jvmTest
./gradlew :nwc-kmp:build
./gradlew publishToMavenLocal
```

Publish all modules locally together so the client's project dependencies are
available to external consumers at the same version.
