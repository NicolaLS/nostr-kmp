import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    jvm()
    androidTarget()

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }

}

android {
    namespace = "io.github.nicolals.nostr.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
