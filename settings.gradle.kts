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

includeBuild("/Users/sus/Misc/GitHub/kotlin-multiplatform-libsodium") {
    dependencySubstitution {
        substitute(module("com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings"))
            .using(project(":multiplatform-crypto-libsodium-bindings"))
    }
}

rootProject.name = "nostr-kmp"
include(":nostr-core")
include(":nostr-codec-kotlinx")
include(":nostr-crypto")
include(":nostr-transport-ktor")
include(":nostr-runtime-coroutines")
include(":nostr-sdk")
include(":nips")
include(":nips:nip01")
include(":nips:nip04")
include(":nips:nip42")
include(":nips:nip44")
include(":nips:nip45")
include(":nips:nip47")
