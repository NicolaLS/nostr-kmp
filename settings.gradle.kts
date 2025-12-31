pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
include(":nostr-sdk")
include(":nips:metadata")
include(":nips:nip04")
include(":nips:nip44")
