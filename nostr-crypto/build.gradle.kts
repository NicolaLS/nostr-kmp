import com.android.build.api.dsl.androidLibrary
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

description = "Nostr crypto primitives backed by libsodium and secp256k1-kmp."

kotlin {
    jvm()

    androidLibrary {
        namespace = "io.github.nicolals.nostr.crypto"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":nostr-core"))
                implementation(libs.ionspin.libsodium)
                implementation(libs.secp256k1.kmp)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.secp256k1.kmp.jni.jvm)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(project(":nostr-codec-kotlinx"))
                implementation(project(":nips:nip04"))
                implementation(project(":nips:nip44"))
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.secp256k1.kmp.jni.android)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    val props = mapOf(
        "nostr.benchmarks" to (System.getProperty("nostr.benchmarks") ?: System.getenv("NOSTR_BENCHMARKS")),
        "nostr.benchmark.warmup" to (System.getProperty("nostr.benchmark.warmup") ?: System.getenv("NOSTR_BENCHMARK_WARMUP")),
        "nostr.benchmark.iterations" to (System.getProperty("nostr.benchmark.iterations") ?: System.getenv("NOSTR_BENCHMARK_ITERATIONS")),
        "nostr.benchmark.batchScale" to (System.getProperty("nostr.benchmark.batchScale") ?: System.getenv("NOSTR_BENCHMARK_BATCH_SCALE")),
        "nostr.benchmark.label" to (System.getProperty("nostr.benchmark.label") ?: System.getenv("NOSTR_BENCHMARK_LABEL")),
        "nostr.benchmark.reportDir" to (System.getProperty("nostr.benchmark.reportDir") ?: System.getenv("NOSTR_BENCHMARK_REPORT_DIR")),
    )
    props.forEach { (key, value) ->
        if (!value.isNullOrBlank()) {
            systemProperty(key, value)
        }
    }
}
