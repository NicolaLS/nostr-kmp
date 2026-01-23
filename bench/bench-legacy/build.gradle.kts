plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":bench-common"))

    implementation("io.github.nicolals:nostr-runtime-coroutines")
    implementation("io.github.nicolals:nostr-transport-ktor")
    implementation("io.github.nicolals:nostr-codec-kotlinx-serialization")
    implementation("io.github.nicolals:nostr-crypto")
    implementation("io.github.nicolals:nostr-core")
    implementation("io.github.nicolals:nostr-nip04")
    implementation("io.github.nicolals:nostr-nip44")

    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("io.github.nicolals.nostr.bench.legacy.BenchLegacyMainKt")
}
