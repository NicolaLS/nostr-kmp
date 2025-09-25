plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.nicolals"
version = "1.0.0"

kotlin {
    jvmToolchain(21)

    jvm()
    androidTarget()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":nostr-core"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":nostr-codec-kotlinx-serialization"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

android {
    namespace = "io.github.nicolals.nostr.runtime.coroutines"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
