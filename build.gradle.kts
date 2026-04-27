@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import com.android.build.api.dsl.androidLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

allprojects {
    group = "io.github.nicolals"
    version = "0.3.1-SNAPSHOT"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(libs.versions.jvm.get().toInt())
            applyDefaultHierarchyTemplate()

            iosX64()
            iosArm64()
            iosSimulatorArm64()

            compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
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

            if (
                rootProject.hasProperty("signing.keyId") ||
                rootProject.hasProperty("signingInMemoryKey") ||
                rootProject.hasProperty("signingInMemoryKeyId")
            ) {
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

gradle.projectsEvaluated {
    // Keep compile/package work parallel, but serialize Maven Central uploads to avoid
    // Central Portal rate limits when this KMP project publishes many artifacts.
    val mavenCentralUploadTasks = allprojects
        .flatMap { project ->
            project.tasks.withType(PublishToMavenRepository::class.java)
                .matching { it.repository.name == "mavenCentral" }
                .toList()
        }
        .sortedBy { it.path }

    mavenCentralUploadTasks.zipWithNext().forEach { (previous, next) ->
        next.mustRunAfter(previous)
    }
}
