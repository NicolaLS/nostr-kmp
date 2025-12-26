import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

description = "Core Nostr types and utilities for Kotlin Multiplatform."

kotlin {
    androidLibrary {
        namespace = "io.github.nicolals.nostr.core"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.okio)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
