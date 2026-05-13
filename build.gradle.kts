@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

allprojects {
    group = "io.github.nicolals"
    version = "0.3.2-SNAPSHOT"
}

subprojects {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

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
            targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
                compileSdk = libs.versions.android.compileSdk.get().toInt()
                minSdk = libs.versions.android.minSdk.get().toInt()
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
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
    // Keep compile/package work parallel and allow each module to upload its own
    // publications together, but serialize uploads between modules to reduce
    // Central Portal rate-limit pressure.
    val mavenCentralUploadTasksByProject = allprojects
        .map { project ->
            project to project.tasks.withType(PublishToMavenRepository::class.java)
                .matching { it.repository.name == "mavenCentral" }
                .toList()
        }
        .filter { (_, tasks) -> tasks.isNotEmpty() }
        .sortedBy { (project, _) -> project.path }

    mavenCentralUploadTasksByProject.zipWithNext().forEach { taskGroups ->
        val previousTasks = taskGroups.first.second
        val nextTasks = taskGroups.second.second

        nextTasks.forEach { next ->
            previousTasks.forEach { previous ->
                next.mustRunAfter(previous)
            }
        }
    }
}
