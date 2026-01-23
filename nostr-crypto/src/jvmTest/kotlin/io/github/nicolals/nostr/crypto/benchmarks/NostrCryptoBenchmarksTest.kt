package io.github.nicolals.nostr.crypto.benchmarks

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec
import io.github.nicolals.nostr.core.nip.SimpleNipModuleContext
import io.github.nicolals.nostr.crypto.NostrCrypto
import java.nio.file.Paths
import kotlin.test.Test

class NostrCryptoBenchmarksTest {
    @Test
    fun runBenchmarks() {
        if (!benchmarksEnabled()) {
            println("Nostr crypto benchmarks are disabled. Set -Dnostr.benchmarks=true or NOSTR_BENCHMARKS=true to enable.")
            return
        }

        LibsodiumInitializer.initializeWithCallback {}

        val config = BenchmarkConfig.fromSystem()
        val ctx = SimpleNipModuleContext(KotlinxNostrJsonCodec(), NostrCrypto)
        val runner = BenchmarkRunner(config)

        val results = BenchmarkSuite.scenarios(ctx, config.batchScale).map { scenario ->
            runner.runScenario(scenario)
        }

        val report = BenchmarkReport.create(config, results)
        val reportText = report.toMarkdown()
        println(reportText)

        val outputDir = config.reportDir?.let { Paths.get(it) } ?: Paths.get("build", "benchmarks")
        val reportPaths = BenchmarkReportWriter.write(report, outputDir)
        println("Benchmark report written to ${reportPaths.markdown.toAbsolutePath()}")
        println("Benchmark CSV written to ${reportPaths.csv.toAbsolutePath()}")
    }
}
