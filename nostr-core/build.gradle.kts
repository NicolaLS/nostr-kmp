import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
    // alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // NOTE: Sets the toolchain for Java compile task as well.
    // NOTE: Gradle tasks fail if this version is not present. To streamline contributing to the
    // library consider adding a toolchain resolver plugin to `settings.gradle`.
    // TODO: Find out if I still need to set jvmTarget ie if it will default to 1.8 if not set..
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    jvm()
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        if (konanTarget.family == Family.IOS) {
            compilations["main"].cinterops.create("sha256") {
                defFile(project.file("src/nativeInterop/cinterop/sha256.def"))
            }
        }
    }

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
