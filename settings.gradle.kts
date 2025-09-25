pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "1.9.22"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nostr-kmp"
include(":nostr-core")
include(":nostr-codec-kotlinx-serialization")
include(":nostr-runtime-coroutines")
include(":nostr-transport-ktor")
include(":nostr-crypto")
