@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import com.android.build.api.dsl.androidLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

allprojects {
    group = "io.github.nicolals"
    version = "0.3.0-SNAPSHOT"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(17)
            applyDefaultHierarchyTemplate()

            iosX64()
            iosArm64()
            iosSimulatorArm64()
        }
    }

    plugins.withId("com.android.kotlin.multiplatform.library") {
        extensions.configure<KotlinMultiplatformExtension> {
            @Suppress("UnstableApiUsage")
            androidLibrary {
                compileSdk = libs.versions.android.compileSdk.get().toInt()
                minSdk = libs.versions.android.minSdk.get().toInt()
            }
        }
    }

    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension>("mavenPublishing") {
            publishToMavenCentral()

            if (rootProject.hasProperty("signing.keyId")) {
                signAllPublications()
            }

            coordinates(group.toString(), project.name, version.toString())

            pom {
                name.set(project.name)
                description.set(project.description ?: "Kotlin Multiplatform Nostr SDK module.")
                inceptionYear.set("2025")
                url.set("https://github.com/NicolaLS/nostr-kmp")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://mit-license.org/")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("NicolaLS")
                        name.set("Nicola Leonardo Susca")
                        url.set("https://github.com/NicolaLS")
                    }
                }
                scm {
                    url.set("https://github.com/NicolaLS/nostr-kmp")
                    connection.set("scm:git:https://github.com/NicolaLS/nostr-kmp.git")
                    developerConnection.set("scm:git:ssh://git@github.com/NicolaLS/nostr-kmp.git")
                }
            }
        }
    }
}
