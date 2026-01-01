import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

description = "NIP-47 Nostr Wallet Connect support."

kotlin {
    jvm()

    androidLibrary {
        namespace = "io.github.nicolals.nostr.nip47"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":nostr-core"))
                api(project(":nips:nip04"))
                api(project(":nips:nip44"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
