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
                api(project(":nips:nip04"))
                api(project(":nips:nip42"))
                api(project(":nips:nip44"))
                api(project(":nips:nip45"))
                api(project(":nips:nip47"))
            }
        }
    }
}
