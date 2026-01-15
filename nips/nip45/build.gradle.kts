import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

description = "NIP-45 event count messages (COUNT request/response)."

kotlin {
    jvm()

    androidLibrary {
        namespace = "io.github.nicolals.nostr.nip45"
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
            }
        }
    }
}
