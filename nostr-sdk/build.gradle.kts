import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

description = "Kotlin Multiplatform Nostr SDK bundle."

kotlin {
    androidLibrary {
        namespace = "io.github.nicolals.nostr.sdk"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":nostr-core"))
                api(project(":nostr-codec-kotlinx"))
                api(project(":nostr-crypto"))
                api(project(":nostr-transport-ktor"))
                api(project(":nips:nip01"))
                api(project(":nips:nip04"))
                api(project(":nips:nip42"))
                api(project(":nips:nip44"))
                api(project(":nips:nip45"))
                api(project(":nips:nip47"))
            }
        }
    }
}

// Ensure publishing this module also publishes all its dependencies first
val dependencyProjects = listOf(
    ":nostr-core",
    ":nostr-codec-kotlinx",
    ":nostr-crypto",
    ":nostr-transport-ktor",
    ":nips:nip01",
    ":nips:nip04",
    ":nips:nip42",
    ":nips:nip44",
    ":nips:nip45",
    ":nips:nip47",
)

tasks.matching { it.name.startsWith("publish") && it.name.endsWith("ToMavenLocal") }.configureEach {
    dependencyProjects.forEach { depProject ->
        dependsOn("$depProject:$name")
    }
}
