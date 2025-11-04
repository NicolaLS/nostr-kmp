plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
}

kotlin {
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    jvm()
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

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
        val iosMain by getting
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
        consumerProguardFiles("consumer-proguard-rules.pro")
    }
}
