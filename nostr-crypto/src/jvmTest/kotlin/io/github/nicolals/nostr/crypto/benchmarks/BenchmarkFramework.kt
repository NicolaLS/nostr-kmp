package io.github.nicolals.nostr.crypto.benchmarks

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class BenchmarkConfig(
    val warmupIterations: Int,
    val measureIterations: Int,
    val batchScale: Int,
    val label: String?,
    val reportDir: String?,
) {
    companion object {
        fun fromSystem(): BenchmarkConfig = BenchmarkConfig(
            warmupIterations = readInt("nostr.benchmark.warmup", "NOSTR_BENCHMARK_WARMUP", 50),
            measureIterations = readInt("nostr.benchmark.iterations", "NOSTR_BENCHMARK_ITERATIONS", 200),
            batchScale = readInt("nostr.benchmark.batchScale", "NOSTR_BENCHMARK_BATCH_SCALE", 1),
            label = readString("nostr.benchmark.label", "NOSTR_BENCHMARK_LABEL"),
            reportDir = readString("nostr.benchmark.reportDir", "NOSTR_BENCHMARK_REPORT_DIR"),
        )

        private fun readInt(property: String, env: String, default: Int): Int {
            val raw = System.getProperty(property) ?: System.getenv(env)
            val value = raw?.toIntOrNull()
            return if (value != null && value > 0) value else default
        }

        private fun readString(property: String, env: String): String? =
            System.getProperty(property) ?: System.getenv(env)
    }
}

internal fun benchmarksEnabled(): Boolean {
    val raw = System.getProperty("nostr.benchmarks")
        ?: System.getenv("NOSTR_BENCHMARKS")
        ?: return false
    return raw.equals("true", ignoreCase = true) || raw == "1" || raw.equals("yes", ignoreCase = true)
}

internal interface BenchmarkScenario {
    val name: String
    val description: String
    val operationsPerRun: Int get() = 1

    fun setup() {}
    fun run()
    fun teardown() {}
}

internal data class BenchmarkResult(
    val scenario: String,
    val iterations: Int,
    val opsPerRun: Int,
    val totalOps: Long,
    val totalTimeNs: Long,
    val averageTimeNs: Double,
    val minTimeNs: Double,
    val maxTimeNs: Double,
    val opsPerSecond: Double,
) {
    val averageTimeMs: Double get() = averageTimeNs / 1_000_000.0
    val minTimeMs: Double get() = minTimeNs / 1_000_000.0
    val maxTimeMs: Double get() = maxTimeNs / 1_000_000.0
    val averageTimeUs: Double get() = averageTimeNs / 1_000.0
    val minTimeUs: Double get() = minTimeNs / 1_000.0
    val maxTimeUs: Double get() = maxTimeNs / 1_000.0
}

internal class BenchmarkRunner(
    private val config: BenchmarkConfig,
) {
    fun runScenario(scenario: BenchmarkScenario): BenchmarkResult {
        scenario.setup()

        repeat(config.warmupIterations) { scenario.run() }

        val opsPerRun = scenario.operationsPerRun.coerceAtLeast(1)
        var total = 0L
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE

        repeat(config.measureIterations) {
            val start = System.nanoTime()
            scenario.run()
            val elapsed = System.nanoTime() - start
            total += elapsed
            if (elapsed < min) min = elapsed
            if (elapsed > max) max = elapsed
        }

        scenario.teardown()

        val totalOps = config.measureIterations.toLong() * opsPerRun
        val average = if (totalOps == 0L) 0.0 else total.toDouble() / totalOps
        val ops = if (total == 0L) Double.POSITIVE_INFINITY
        else totalOps / (total / 1_000_000_000.0)

        return BenchmarkResult(
            scenario = scenario.name,
            iterations = config.measureIterations,
            opsPerRun = opsPerRun,
            totalOps = totalOps,
            totalTimeNs = total,
            averageTimeNs = average,
            minTimeNs = if (min == Long.MAX_VALUE) 0.0 else min.toDouble() / opsPerRun,
            maxTimeNs = if (max == Long.MIN_VALUE) 0.0 else max.toDouble() / opsPerRun,
            opsPerSecond = ops,
        )
    }
}

internal data class BenchmarkReport(
    val label: String?,
    val timestamp: String,
    val os: String,
    val osVersion: String,
    val arch: String,
    val javaVersion: String,
    val kotlinVersion: String,
    val jniAvailable: Boolean?,
    val warmupIterations: Int,
    val measureIterations: Int,
    val batchScale: Int,
    val results: List<BenchmarkResult>,
) {
    fun toMarkdown(): String = buildString {
        appendLine("# Nostr Crypto Benchmark Results")
        appendLine()
        appendLine("- Label: ${label ?: "unspecified"}")
        appendLine("- Timestamp: $timestamp")
        appendLine("- OS: $os $osVersion ($arch)")
        appendLine("- Java: $javaVersion")
        appendLine("- Kotlin: $kotlinVersion")
        appendLine("- JNI available: ${jniAvailable ?: "unknown"}")
        appendLine("- Warmup iterations: $warmupIterations")
        appendLine("- Measure iterations: $measureIterations")
        appendLine("- Batch scale: $batchScale")
        appendLine("- Times are per operation (per op), derived from ops/run.")
        appendLine()
        appendLine("| Scenario | Ops/run | Iterations | Avg (us/op) | Min (us/op) | Max (us/op) | Ops/sec |")
        appendLine("|----------|---------|------------|-------------|-------------|-------------|---------|")
        results.forEach { result ->
            appendLine(
                "| ${result.scenario} | ${result.opsPerRun} | ${result.iterations} | ${formatUs(result.averageTimeUs)} | " +
                    "${formatUs(result.minTimeUs)} | ${formatUs(result.maxTimeUs)} | ${formatOps(result.opsPerSecond)} |",
            )
        }
    }

    fun toCsv(): String = buildString {
        appendLine(
            listOf(
                "label",
                "timestamp",
                "os",
                "os_version",
                "arch",
                "java_version",
                "kotlin_version",
                "jni_available",
                "warmup_iterations",
                "measure_iterations",
                "batch_scale",
                "scenario",
                "ops_per_run",
                "iterations",
                "total_ops",
                "avg_us_per_op",
                "min_us_per_op",
                "max_us_per_op",
                "ops_per_sec",
                "total_time_ns",
            ).joinToString(","),
        )
        results.forEach { result ->
            appendLine(
                listOf(
                    csv(label ?: "unspecified"),
                    csv(timestamp),
                    csv(os),
                    csv(osVersion),
                    csv(arch),
                    csv(javaVersion),
                    csv(kotlinVersion),
                    csv(jniAvailable?.toString() ?: "unknown"),
                    warmupIterations.toString(),
                    measureIterations.toString(),
                    batchScale.toString(),
                    csv(result.scenario),
                    result.opsPerRun.toString(),
                    result.iterations.toString(),
                    result.totalOps.toString(),
                    formatUs(result.averageTimeUs),
                    formatUs(result.minTimeUs),
                    formatUs(result.maxTimeUs),
                    formatOps(result.opsPerSecond),
                    result.totalTimeNs.toString(),
                ).joinToString(","),
            )
        }
    }

    companion object {
        fun create(config: BenchmarkConfig, results: List<BenchmarkResult>): BenchmarkReport = BenchmarkReport(
            label = config.label,
            timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            os = System.getProperty("os.name") ?: "unknown",
            osVersion = System.getProperty("os.version") ?: "unknown",
            arch = System.getProperty("os.arch") ?: "unknown",
            javaVersion = System.getProperty("java.version") ?: "unknown",
            kotlinVersion = KotlinVersion.CURRENT.toString(),
            jniAvailable = jniAvailableOrNull(),
            warmupIterations = config.warmupIterations,
            measureIterations = config.measureIterations,
            batchScale = config.batchScale,
            results = results,
        )
    }
}

internal object BenchmarkReportWriter {
    data class ReportPaths(val markdown: Path, val csv: Path)

    fun write(report: BenchmarkReport, outputDir: Path): ReportPaths {
        Files.createDirectories(outputDir)
        val safeTimestamp = report.timestamp.replace(':', '-').replace(".", "-")
        val safeLabel = report.label
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9_-]+"), "-")
            ?.trim('-')
            .orEmpty()
        val labelSuffix = if (safeLabel.isBlank()) "run" else safeLabel
        val baseName = "nostr-crypto-benchmark-$labelSuffix-$safeTimestamp"
        val markdownPath = outputDir.resolve("$baseName.md")
        val csvPath = outputDir.resolve("$baseName.csv")
        Files.writeString(markdownPath, report.toMarkdown())
        Files.writeString(csvPath, report.toCsv())
        return ReportPaths(markdown = markdownPath, csv = csvPath)
    }
}

internal fun jniAvailableOrNull(): Boolean? {
    return try {
        val clazz = Class.forName("com.ionspin.kotlin.crypto.LibsodiumInitializer")
        val instance = clazz.getField("INSTANCE").get(null)
        val getter = clazz.methods.firstOrNull { it.name == "getJniAvailable" && it.parameterCount == 0 }
        getter?.invoke(instance) as? Boolean
    } catch (_: Throwable) {
        null
    }
}

private fun formatUs(value: Double): String = String.format(Locale.US, "%.3f", value)

private fun formatOps(value: Double): String = String.format(Locale.US, "%.0f", value)

private fun csv(value: String): String {
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}
