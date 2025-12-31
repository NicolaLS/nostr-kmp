import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

description = "Nostr codec implementation using kotlinx serialization."

kotlin {
    jvm()

    androidLibrary {
        namespace = "io.github.nicolals.nostr.codec.kotlinx"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":nostr-core"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
