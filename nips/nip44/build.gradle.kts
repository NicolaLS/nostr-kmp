plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
    // alias(libs.plugins.kotlinSerialization)
}

// Maven Central Namespace
group = "io.github.nicolals"
version = "1.0.0"


kotlin {
    // NOTE: Sets the toolchain for Java compile task as well.
    // NOTE: Gradle tasks fail if this version is not present. To streamline contributing to the
    // library consider adding a toolchain resolver plugin to `settings.gradle`.
    // TODO: Find out if I still need to set jvmTarget ie if it will default to 1.8 if not set..
    jvmToolchain(21)

    jvm {
        testRuns["test"].executionTask.configure {
            useJUnit()
        }
    }
    applyDefaultHierarchyTemplate()

    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.acinq.secp256k1)
                implementation(libs.multiplatform.crypto.libsodium.bindings)
                implementation(libs.kotlinx.coroutines.core)
                implementation(project(":nostr-core"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.acinq.secp256k1.jni.jvm)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                runtimeOnly(libs.acinq.secp256k1.jni.jvm)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.acinq.secp256k1.jni.android)
            }
        }
        val iosMain by getting
    }
}

android {
    namespace = "io.github.nicolals.nostr.nips.nip44"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-proguard-rules.pro")
    }
}
