pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "nostr-bench"

include(":bench-common")
include(":relay-sim")
include(":bench-new")
include(":bench-legacy")

// Use the local SDK modules without publishing to Maven
includeBuild("..")
