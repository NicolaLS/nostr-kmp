import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
                implementation(libs.acinq.secp256k1)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.acinq.secp256k1.jni.jvm)
                implementation(libs.acinq.secp256k1.jni.jvm.darwin)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.acinq.secp256k1.jni.android)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.acinq.secp256k1.jni.jvm)
                implementation(libs.acinq.secp256k1.jni.jvm.darwin)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    namespace = "io.github.nicolals.nostr.crypto"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
