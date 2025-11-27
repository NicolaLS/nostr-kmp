plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
}

kotlin {
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
                implementation(project(":nostr-core"))
                implementation(libs.acinq.secp256k1)
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
    namespace = "io.github.nicolals.nostr.nips.nip04"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-proguard-rules.pro")
    }
}
