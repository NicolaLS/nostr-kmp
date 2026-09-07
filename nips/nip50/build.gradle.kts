plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

description = "NIP-50 typed search filter extensions."

kotlin {
    jvm()

    android {
        namespace = "io.github.nicolals.nostr.nip50"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":nostr-core"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(project(":nostr-codec-kotlinx"))
                implementation(project(":nips:nip01"))
                implementation(project(":nips:nip45"))
            }
        }
    }
}
