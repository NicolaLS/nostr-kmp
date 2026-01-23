# Nostr Crypto Benchmarking Plan

## Overview

This plan outlines how to benchmark the JNI vs JNA performance difference for libsodium operations in the context of a cross-platform mobile app using nostr-kmp.

## Goals

1. Measure real-world performance gains (or lack thereof) from JNI migration
2. Test with realistic nostr payloads and operations
3. Compare across Android and iOS
4. Determine if maintaining a JNI fork is worth the effort

---

## Part 1: Project Setup

### 1.1 Create a New KMP Benchmark Project

```bash
# Create new project directory alongside nostr-kmp
mkdir nostr-benchmark
cd nostr-benchmark
```

Use the Kotlin Multiplatform wizard or manually create a KMP project targeting:
- `androidApp` - Android application module
- `iosApp` - iOS application (via Xcode)
- `shared` - Shared KMP module with benchmark logic

### 1.2 Configure Local Dependencies

In your benchmark project's `settings.gradle.kts`:

```kotlin
// Include the local libsodium fork
includeBuild("/Users/sus/Misc/GitHub/kotlin-multiplatform-libsodium") {
    dependencySubstitution {
        substitute(module("com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings"))
            .using(project(":multiplatform-crypto-libsodium-bindings"))
    }
}

// Include local nostr-kmp
includeBuild("/Users/sus/Misc/Nostr/nostr-kmp") {
    dependencySubstitution {
        substitute(module("io.github.nicola.nostr:nostr-crypto"))
            .using(project(":nostr-crypto"))
        // Add other nostr-kmp modules as needed
    }
}
```

In `shared/build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.nicola.nostr:nostr-crypto:VERSION")
            // Other nostr-kmp dependencies
        }
    }
}
```

### 1.3 Create JNA-only Comparison Branch

To compare JNA vs JNI fairly:

```bash
cd /Users/sus/Misc/GitHub/kotlin-multiplatform-libsodium
git checkout -b jna-baseline main  # Branch from main (pure JNA)
```

You can then switch between branches to compare:
- `jni` branch: JNI-accelerated (this implementation)
- `jna-baseline` branch: Original JNA-only

---

## Part 2: Benchmark Scenarios

### 2.1 Nostr Operations to Benchmark

Based on the functions used by nostr-kmp and typical nostr client operations:

| Operation | Libsodium Functions Used | Typical Frequency |
|-----------|-------------------------|-------------------|
| Generate keypair | `randombytes_buf` | Once per user |
| Create event ID | `sha256` | Every event |
| Sign event | `randombytes_buf`, `sha256` (schnorr internals) | Every event |
| Verify event | `sha256` (schnorr internals) | Every received event |
| NIP-04 encrypt | `sha256`, `chacha20` | Per DM |
| NIP-04 decrypt | `sha256`, `chacha20` | Per DM |
| NIP-44 encrypt | `sha256`, `chacha20`, `randombytes_buf` | Per DM (modern) |
| NIP-44 decrypt | `sha256`, `chacha20` | Per DM (modern) |
| Base64 encode/decode | `sodium_bin2base64`, `sodium_base642bin` | Frequently |

### 2.2 Realistic Payload Sizes

```kotlin
object NostrPayloadSizes {
    // Event content sizes (bytes)
    val TINY_NOTE = 32          // "gm"
    val SHORT_NOTE = 280        // Tweet-length
    val MEDIUM_NOTE = 1024      // ~1KB post
    val LONG_NOTE = 4096        // Long-form content
    val ARTICLE = 16384         // NIP-23 article

    // NIP-47 (Nostr Wallet Connect) payloads
    val NWC_REQUEST = 256       // Typical pay_invoice request
    val NWC_RESPONSE = 512      // Typical response with preimage

    // NIP-04/44 DM sizes
    val SHORT_DM = 128          // Quick message
    val MEDIUM_DM = 512         // Normal conversation
    val LONG_DM = 2048          // Long message

    // Batch operations (simulating feed load)
    val EVENTS_PER_FEED_PAGE = 20
    val EVENTS_PER_PROFILE_LOAD = 50
}
```

---

## Part 3: Benchmark Implementation

### 3.1 Common Benchmark Interface

Create in `shared/src/commonMain/kotlin/`:

```kotlin
// BenchmarkScenario.kt
interface BenchmarkScenario {
    val name: String
    val description: String

    suspend fun setup()
    suspend fun run(): BenchmarkResult
    suspend fun teardown()
}

data class BenchmarkResult(
    val scenario: String,
    val iterations: Int,
    val totalTimeMs: Long,
    val averageTimeMs: Double,
    val minTimeMs: Long,
    val maxTimeMs: Long,
    val opsPerSecond: Double
)

// BenchmarkRunner.kt
class BenchmarkRunner {
    suspend fun runScenario(
        scenario: BenchmarkScenario,
        warmupIterations: Int = 100,
        measureIterations: Int = 1000
    ): BenchmarkResult {
        scenario.setup()

        // Warmup
        repeat(warmupIterations) { scenario.run() }

        // Measure
        val times = mutableListOf<Long>()
        repeat(measureIterations) {
            val start = currentTimeNanos()
            scenario.run()
            times.add(currentTimeNanos() - start)
        }

        scenario.teardown()

        val totalNs = times.sum()
        return BenchmarkResult(
            scenario = scenario.name,
            iterations = measureIterations,
            totalTimeMs = totalNs / 1_000_000,
            averageTimeMs = (totalNs / measureIterations) / 1_000_000.0,
            minTimeMs = times.min() / 1_000_000,
            maxTimeMs = times.max() / 1_000_000,
            opsPerSecond = measureIterations / (totalNs / 1_000_000_000.0)
        )
    }
}

expect fun currentTimeNanos(): Long
```

### 3.2 Benchmark Scenarios Implementation

```kotlin
// scenarios/Sha256Benchmark.kt
class Sha256Benchmark(private val payloadSize: Int) : BenchmarkScenario {
    override val name = "SHA256-${payloadSize}B"
    override val description = "Hash $payloadSize bytes with SHA256"

    private lateinit var data: ByteArray

    override suspend fun setup() {
        data = Random.nextBytes(payloadSize)
    }

    override suspend fun run(): BenchmarkResult {
        NostrCrypto.sha256(data)
        return BenchmarkResult(name, 1, 0, 0.0, 0, 0, 0.0) // Placeholder
    }

    override suspend fun teardown() {}
}

// scenarios/EventCreationBenchmark.kt
class EventCreationBenchmark(private val contentSize: Int) : BenchmarkScenario {
    override val name = "EventCreation-${contentSize}B"
    override val description = "Create and sign event with $contentSize byte content"

    private lateinit var privateKey: ByteArray
    private lateinit var content: String

    override suspend fun setup() {
        privateKey = NostrCrypto.generateSecretKey()
        content = "x".repeat(contentSize)
    }

    override suspend fun run(): BenchmarkResult {
        // This exercises: randombytes_buf, sha256, schnorr signing
        val event = NostrEvent.create(
            kind = 1,
            content = content,
            tags = emptyList(),
            privateKey = privateKey
        )
        return BenchmarkResult(name, 1, 0, 0.0, 0, 0, 0.0)
    }

    override suspend fun teardown() {}
}

// scenarios/EventVerificationBenchmark.kt
class EventVerificationBenchmark(private val contentSize: Int) : BenchmarkScenario {
    override val name = "EventVerify-${contentSize}B"
    override val description = "Verify event signature with $contentSize byte content"

    private lateinit var event: NostrEvent

    override suspend fun setup() {
        val privateKey = NostrCrypto.generateSecretKey()
        event = NostrEvent.create(
            kind = 1,
            content = "x".repeat(contentSize),
            tags = emptyList(),
            privateKey = privateKey
        )
    }

    override suspend fun run(): BenchmarkResult {
        event.verify()
        return BenchmarkResult(name, 1, 0, 0.0, 0, 0, 0.0)
    }

    override suspend fun teardown() {}
}

// scenarios/Nip04EncryptBenchmark.kt
class Nip04EncryptBenchmark(private val messageSize: Int) : BenchmarkScenario {
    override val name = "NIP04-Encrypt-${messageSize}B"
    override val description = "NIP-04 encrypt $messageSize byte message"

    private lateinit var senderPrivateKey: ByteArray
    private lateinit var receiverPublicKey: ByteArray
    private lateinit var message: String

    override suspend fun setup() {
        senderPrivateKey = NostrCrypto.generateSecretKey()
        val receiverPrivateKey = NostrCrypto.generateSecretKey()
        receiverPublicKey = NostrCrypto.getPublicKey(receiverPrivateKey)
        message = "x".repeat(messageSize)
    }

    override suspend fun run(): BenchmarkResult {
        Nip04.encrypt(message, senderPrivateKey, receiverPublicKey)
        return BenchmarkResult(name, 1, 0, 0.0, 0, 0, 0.0)
    }

    override suspend fun teardown() {}
}

// scenarios/Nip44EncryptBenchmark.kt
class Nip44EncryptBenchmark(private val messageSize: Int) : BenchmarkScenario {
    override val name = "NIP44-Encrypt-${messageSize}B"
    override val description = "NIP-44 encrypt $messageSize byte message"

    private lateinit var senderPrivateKey: ByteArray
    private lateinit var receiverPublicKey: ByteArray
    private lateinit var message: String

    override suspend fun setup() {
        senderPrivateKey = NostrCrypto.generateSecretKey()
        val receiverPrivateKey = NostrCrypto.generateSecretKey()
        receiverPublicKey = NostrCrypto.getPublicKey(receiverPrivateKey)
        message = "x".repeat(messageSize)
    }

    override suspend fun run(): BenchmarkResult {
        Nip44.encrypt(message, senderPrivateKey, receiverPublicKey)
        return BenchmarkResult(name, 1, 0, 0.0, 0, 0, 0.0)
    }

    override suspend fun teardown() {}
}

// scenarios/FeedLoadSimulation.kt
class FeedLoadSimulationBenchmark(private val eventCount: Int) : BenchmarkScenario {
    override val name = "FeedLoad-${eventCount}events"
    override val description = "Simulate loading and verifying $eventCount events"

    private lateinit var events: List<NostrEvent>

    override suspend fun setup() {
        // Pre-create events to simulate receiving them
        events = (1..eventCount).map {
            val privateKey = NostrCrypto.generateSecretKey()
            NostrEvent.create(
                kind = 1,
                content = "Event #$it with some content: " + "x".repeat(200),
                tags = emptyList(),
                privateKey = privateKey
            )
        }
    }

    override suspend fun run(): BenchmarkResult {
        // Verify all events (what a client does on feed load)
        events.forEach { it.verify() }
        return BenchmarkResult(name, 1, 0, 0.0, 0, 0, 0.0)
    }

    override suspend fun teardown() {}
}

// scenarios/NwcWorkflowBenchmark.kt
class NwcWorkflowBenchmark : BenchmarkScenario {
    override val name = "NWC-Workflow"
    override val description = "Complete NIP-47 request/response cycle"

    private lateinit var clientPrivateKey: ByteArray
    private lateinit var walletPrivateKey: ByteArray
    private lateinit var walletPublicKey: ByteArray
    private val request = """{"method":"pay_invoice","params":{"invoice":"lnbc..."}}"""

    override suspend fun setup() {
        clientPrivateKey = NostrCrypto.generateSecretKey()
        walletPrivateKey = NostrCrypto.generateSecretKey()
        walletPublicKey = NostrCrypto.getPublicKey(walletPrivateKey)
    }

    override suspend fun run(): BenchmarkResult {
        // Client encrypts request
        val encryptedRequest = Nip04.encrypt(request, clientPrivateKey, walletPublicKey)

        // Create request event
        val requestEvent = NostrEvent.create(
            kind = 23194,
            content = encryptedRequest,
            tags = listOf(listOf("p", walletPublicKey.toHex())),
            privateKey = clientPrivateKey
        )

        // Wallet receives and verifies
        requestEvent.verify()

        // Wallet decrypts
        val decryptedRequest = Nip04.decrypt(
            requestEvent.content,
            walletPrivateKey,
            NostrCrypto.getPublicKey(clientPrivateKey)
        )

        // Wallet creates response
        val response = """{"result_type":"pay_invoice","result":{"preimage":"abc123"}}"""
        val encryptedResponse = Nip04.encrypt(response, walletPrivateKey, NostrCrypto.getPublicKey(clientPrivateKey))

        val responseEvent = NostrEvent.create(
            kind = 23195,
            content = encryptedResponse,
            tags = listOf(listOf("e", requestEvent.id)),
            privateKey = walletPrivateKey
        )

        // Client verifies and decrypts response
        responseEvent.verify()
        Nip04.decrypt(responseEvent.content, clientPrivateKey, walletPublicKey)

        return BenchmarkResult(name, 1, 0, 0.0, 0, 0, 0.0)
    }

    override suspend fun teardown() {}
}
```

### 3.3 Benchmark Suite

```kotlin
// BenchmarkSuite.kt
object BenchmarkSuite {
    fun getAllScenarios(): List<BenchmarkScenario> = listOf(
        // SHA256 at various sizes
        Sha256Benchmark(32),
        Sha256Benchmark(256),
        Sha256Benchmark(1024),
        Sha256Benchmark(4096),

        // Event creation
        EventCreationBenchmark(32),
        EventCreationBenchmark(280),
        EventCreationBenchmark(1024),

        // Event verification
        EventVerificationBenchmark(32),
        EventVerificationBenchmark(280),
        EventVerificationBenchmark(1024),

        // NIP-04 encryption
        Nip04EncryptBenchmark(128),
        Nip04EncryptBenchmark(512),
        Nip04EncryptBenchmark(2048),

        // NIP-04 decryption
        Nip04DecryptBenchmark(128),
        Nip04DecryptBenchmark(512),
        Nip04DecryptBenchmark(2048),

        // NIP-44 encryption
        Nip44EncryptBenchmark(128),
        Nip44EncryptBenchmark(512),
        Nip44EncryptBenchmark(2048),

        // NIP-44 decryption
        Nip44DecryptBenchmark(128),
        Nip44DecryptBenchmark(512),
        Nip44DecryptBenchmark(2048),

        // Real-world simulations
        FeedLoadSimulationBenchmark(20),
        FeedLoadSimulationBenchmark(50),
        NwcWorkflowBenchmark(),

        // Base64 operations
        Base64Benchmark(32),
        Base64Benchmark(512),
        Base64Benchmark(4096),

        // Random bytes generation
        RandomBytesBenchmark(32),
        RandomBytesBenchmark(256),
    )
}
```

---

## Part 4: Android Benchmarking

### 4.1 Using AndroidX Benchmark

Add to `androidApp/build.gradle.kts`:

```kotlin
dependencies {
    androidTestImplementation("androidx.benchmark:benchmark-junit4:1.2.0")
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    testBuildType = "release"

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
```

### 4.2 Android Benchmark Tests

```kotlin
// androidApp/src/androidTest/kotlin/NostrCryptoBenchmark.kt
@RunWith(AndroidJUnit4::class)
class NostrCryptoBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Before
    fun setup() = runBlocking {
        LibsodiumInitializer.initialize()
        println("JNI Available: ${LibsodiumInitializer.jniAvailable}")
    }

    @Test
    fun sha256_32bytes() = benchmarkRule.measureRepeated {
        runBlocking {
            val data = runWithTimingDisabled { Random.nextBytes(32).asUByteArray() }
            Hash.sha256(data)
        }
    }

    @Test
    fun sha256_1kb() = benchmarkRule.measureRepeated {
        runBlocking {
            val data = runWithTimingDisabled { Random.nextBytes(1024).asUByteArray() }
            Hash.sha256(data)
        }
    }

    @Test
    fun eventCreation_280bytes() = benchmarkRule.measureRepeated {
        runBlocking {
            val privateKey = runWithTimingDisabled { NostrCrypto.generateSecretKey() }
            NostrEvent.create(
                kind = 1,
                content = "x".repeat(280),
                tags = emptyList(),
                privateKey = privateKey
            )
        }
    }

    @Test
    fun nip04Encrypt_512bytes() = benchmarkRule.measureRepeated {
        runBlocking {
            val (senderKey, receiverPubKey, message) = runWithTimingDisabled {
                val sender = NostrCrypto.generateSecretKey()
                val receiver = NostrCrypto.generateSecretKey()
                Triple(sender, NostrCrypto.getPublicKey(receiver), "x".repeat(512))
            }
            Nip04.encrypt(message, senderKey, receiverPubKey)
        }
    }

    @Test
    fun feedLoad_20events() = benchmarkRule.measureRepeated {
        runBlocking {
            val events = runWithTimingDisabled {
                (1..20).map {
                    val pk = NostrCrypto.generateSecretKey()
                    NostrEvent.create(1, "Event $it content", emptyList(), pk)
                }
            }
            events.forEach { it.verify() }
        }
    }

    @Test
    fun nwcWorkflow() = benchmarkRule.measureRepeated {
        runBlocking {
            // ... full NWC workflow
        }
    }
}
```

### 4.3 Running Android Benchmarks

```bash
# Run benchmarks on connected device
./gradlew :androidApp:connectedAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=NostrCryptoBenchmark

# Results will be in:
# androidApp/build/outputs/connected_android_test_additional_output/
```

---

## Part 5: iOS Benchmarking

### 5.1 XCTest Performance Tests

```swift
// iosApp/iosAppTests/NostrCryptoBenchmarkTests.swift
import XCTest
import shared

class NostrCryptoBenchmarkTests: XCTestCase {

    override func setUp() async throws {
        try await LibsodiumInitializer.shared.initialize()
    }

    func testSha256_32bytes() {
        let data = Data((0..<32).map { _ in UInt8.random(in: 0...255) })

        measure {
            for _ in 0..<1000 {
                _ = NostrCrypto.shared.sha256(data: data)
            }
        }
    }

    func testSha256_1kb() {
        let data = Data((0..<1024).map { _ in UInt8.random(in: 0...255) })

        measure {
            for _ in 0..<1000 {
                _ = NostrCrypto.shared.sha256(data: data)
            }
        }
    }

    func testEventCreation_280bytes() {
        measure {
            for _ in 0..<100 {
                let privateKey = NostrCrypto.shared.generateSecretKey()
                _ = NostrEvent.Companion.shared.create(
                    kind: 1,
                    content: String(repeating: "x", count: 280),
                    tags: [],
                    privateKey: privateKey
                )
            }
        }
    }

    func testFeedLoad_20events() {
        // Pre-create events
        var events: [NostrEvent] = []
        for i in 0..<20 {
            let pk = NostrCrypto.shared.generateSecretKey()
            events.append(NostrEvent.Companion.shared.create(
                kind: 1,
                content: "Event \(i)",
                tags: [],
                privateKey: pk
            ))
        }

        measure {
            for _ in 0..<100 {
                for event in events {
                    _ = event.verify()
                }
            }
        }
    }
}
```

### 5.2 Running iOS Benchmarks

```bash
# Via Xcode
# 1. Open iosApp/iosApp.xcworkspace
# 2. Select test target
# 3. Product > Test (Cmd+U)

# Or via command line
xcodebuild test \
    -workspace iosApp/iosApp.xcworkspace \
    -scheme iosApp \
    -destination 'platform=iOS Simulator,name=iPhone 15' \
    -only-testing:iosAppTests/NostrCryptoBenchmarkTests
```

---

## Part 6: Comparing JNA vs JNI

### 6.1 Comparison Methodology

1. **Run benchmarks with JNI branch:**
   ```bash
   cd /Users/sus/Misc/GitHub/kotlin-multiplatform-libsodium
   git checkout jni
   # Build and run benchmarks
   ```

2. **Run benchmarks with JNA baseline:**
   ```bash
   cd /Users/sus/Misc/GitHub/kotlin-multiplatform-libsodium
   git checkout main  # or jna-baseline
   # Build and run benchmarks
   ```

3. **Compare results** - Look for:
   - Percentage improvement in ops/second
   - Consistency (min/max spread)
   - Impact on real-world scenarios (feed load, NWC workflow)

### 6.2 Expected Results Analysis

| If JNI shows... | Recommendation |
|-----------------|----------------|
| >20% improvement on hot paths | Worth maintaining fork |
| 5-20% improvement | Consider for high-volume use cases |
| <5% improvement | Stick with JNA (simpler maintenance) |
| Regression | Debug JNI implementation |

### 6.3 Factors That May Affect Results

- **Payload size**: JNI overhead matters more for small payloads
- **Device**: Older devices may show more benefit
- **Memory pressure**: JNI avoids some JNA allocations
- **Call frequency**: High-frequency calls benefit more from JNI

---

## Part 7: Results Reporting

### 7.1 Output Format

```kotlin
data class BenchmarkReport(
    val device: String,
    val os: String,
    val osVersion: String,
    val implementation: String, // "JNI" or "JNA"
    val jniAvailable: Boolean,
    val timestamp: String,
    val results: List<BenchmarkResult>
)

fun generateReport(results: List<BenchmarkResult>): String {
    return buildString {
        appendLine("# Nostr Crypto Benchmark Results")
        appendLine()
        appendLine("| Scenario | Iterations | Avg (ms) | Min (ms) | Max (ms) | Ops/sec |")
        appendLine("|----------|------------|----------|----------|----------|---------|")
        results.forEach { r ->
            appendLine("| ${r.scenario} | ${r.iterations} | ${"%.3f".format(r.averageTimeMs)} | ${r.minTimeMs} | ${r.maxTimeMs} | ${"%.0f".format(r.opsPerSecond)} |")
        }
    }
}
```

### 7.2 Comparison Report

```markdown
# JNI vs JNA Comparison

## Test Environment
- Device: Pixel 7 Pro
- Android: 14
- Date: 2024-01-22

## Results

| Scenario | JNA (ops/s) | JNI (ops/s) | Improvement |
|----------|-------------|-------------|-------------|
| SHA256-32B | 45,000 | 52,000 | +15.6% |
| SHA256-1KB | 12,000 | 13,500 | +12.5% |
| EventCreate-280B | 3,200 | 3,600 | +12.5% |
| NIP04-Encrypt-512B | 8,500 | 9,200 | +8.2% |
| FeedLoad-20events | 150 | 168 | +12.0% |
| NWC-Workflow | 420 | 465 | +10.7% |

## Conclusion
[Your analysis here]
```

---

## Quick Start Checklist

- [ ] Create benchmark project with local dependencies
- [ ] Implement common benchmark scenarios
- [ ] Set up Android benchmark module with AndroidX Benchmark
- [ ] Set up iOS benchmark tests with XCTest
- [ ] Run benchmarks on JNI branch
- [ ] Run benchmarks on JNA baseline
- [ ] Generate comparison report
- [ ] Make decision on maintaining fork

---

## Notes

- iOS uses Kotlin/Native which has direct C interop - no JNA/JNI distinction
- The JNI improvement only applies to JVM/Android
- For iOS, compare Kotlin/Native performance vs pure Swift implementations if needed
- Consider battery impact for mobile benchmarks (run plugged in)
- Run multiple times to get stable results
- Use release builds for accurate measurements
