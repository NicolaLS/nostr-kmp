import com.android.build.gradle.LibraryExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget

val groupId = "io.github.nicolals"
val versionName = "0.1.0-SNAPSHOT"

plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

allprojects {
    group = groupId
    version = versionName
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
            when (project.path) {
                ":nips:nip04" -> coordinates(group.toString(), "nostr-nip04", version.toString())
                ":nips:nip42" -> coordinates(group.toString(), "nostr-nip42", version.toString())
                ":nips:nip44" -> coordinates(group.toString(), "nostr-nip44", version.toString())
            }

            publishToMavenCentral()

            pom {
                name.set("Kotlin Multiplatform Nostr SDK")
                description.set("Kotlin Multiplatform Nostr SDK")
                url.set("https://github.com/NicolaLS/nostr-kmp/")
                inceptionYear.set("2025")

                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://mit-license.org/")
                        distribution.set("repo")
                    }
                }

                scm {
                    url.set("https://github.com/NicolaLS/nostr-kmp/")
                    connection.set("scm:git:git://github.com/NicolaLS/nostr-kmp.git")
                    developerConnection.set("scm:git:ssh://git@github.com/NicolaLS/nostr-kmp.git")
                }

                developers {
                    developer {
                        id.set("NicolaLS")
                        name.set("Nicola Susca")
                        url.set("https://github.com/NicolaLS/")
                    }
                }
            }

            if (rootProject.hasProperty("signing.keyId")) {
                signAllPublications()
            }
        }
    }
}
