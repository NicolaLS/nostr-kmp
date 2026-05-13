plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

description = "Coroutines-based runtime for Nostr relay sessions with policy-driven reconnect and retry."

kotlin {
    jvm()

    android {
        namespace = "io.github.nicolals.nostr.runtime.coroutines"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":nostr-core"))
                api(project(":nips:nip01"))
                implementation(project(":nips:nip42"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
