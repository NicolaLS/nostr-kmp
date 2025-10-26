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
    js(IR) {
        browser()
        nodejs()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":nostr-core"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
            }
        }
        val jsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
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
    namespace = "io.github.nicolals.nostr.transport.ktor"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
