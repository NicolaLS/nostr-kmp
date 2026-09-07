import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

description = "Kotlin Multiplatform client for Nostr Wallet Connect (NIP-47)."

kotlin {
    jvm()

    android {
        namespace = "io.github.nicolals.nwc"
    }

    targets.withType<KotlinNativeTarget> {
        binaries.framework {
            baseName = "NwcKmp"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Coroutine scopes and flows are exposed in the client API.
                api(libs.kotlinx.coroutines.core)
                // nostr-core types (PublicKey) are exposed in public API
                api(project(":nostr-core"))
                implementation(project(":nostr-codec-kotlinx"))
                implementation(project(":nostr-runtime-coroutines"))
                implementation(project(":nostr-transport-ktor"))
                implementation(project(":nostr-crypto"))
                implementation(project(":nips:nip44"))
                implementation(project(":nips:nip04"))
                // NIP-47 types (NwcEncryption) are exposed in public API
                api(project(":nips:nip47"))
                // Client factories accept an optional HttpClient.
                api(libs.ktor.client.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

    }
}
