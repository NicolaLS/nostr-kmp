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
include(":nostr-sdk")