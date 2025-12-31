import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

description = "Nostr crypto primitives backed by libsodium and secp256k1-kmp."

kotlin {
    jvm()

    androidLibrary {
        namespace = "io.github.nicolals.nostr.crypto"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":nostr-core"))
                implementation(libs.ionspin.libsodium)
                implementation(libs.secp256k1.kmp)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.secp256k1.kmp.jni.jvm)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.secp256k1.kmp.jni.android)
            }
        }
    }
}
