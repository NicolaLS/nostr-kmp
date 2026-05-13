pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nostr-kmp"
include(":nostr-core")
include(":nostr-codec-kotlinx")
include(":nostr-crypto")
include(":nostr-transport-ktor")
include(":nostr-runtime-coroutines")
include(":nips")
include(":nips:nip01")
include(":nips:nip04")
include(":nips:nip42")
include(":nips:nip44")
include(":nips:nip45")
include(":nips:nip47")
