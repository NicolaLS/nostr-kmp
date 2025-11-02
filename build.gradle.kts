import com.android.build.gradle.LibraryExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget

plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

allprojects {
    group = property("GROUP")!!
    version = property("VERSION_NAME")!!
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure(KotlinMultiplatformExtension::class.java) {
            jvmToolchain(21)
            targets.withType(KotlinAndroidTarget::class.java).configureEach {
                publishLibraryVariants("release")
            }
        }
    }

    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension>("android") {
            val compileSdkVersion = rootProject.property("ANDROID_COMPILE_SDK").toString().toInt()
            val minSdkVersion = rootProject.property("ANDROID_MIN_SDK").toString().toInt()
            compileSdk = compileSdkVersion
            defaultConfig {
                minSdk = minSdkVersion
            }
            @Suppress("UnstableApiUsage")
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                    withJavadocJar()
                }
            }
        }
    }

    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension>("mavenPublishing") {
            if (rootProject.hasProperty("signing.keyId")) {
                signAllPublications()
            }
        }
    }
}
