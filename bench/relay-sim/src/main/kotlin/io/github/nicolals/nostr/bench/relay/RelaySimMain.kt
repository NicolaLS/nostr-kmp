package io.github.nicolals.nostr.bench.relay

import io.github.nicolals.nostr.bench.CliArgs
import io.github.nicolals.nostr.bench.RelayConfig
import io.github.nicolals.nostr.bench.Scenario
import io.github.nicolals.nostr.bench.ScenarioLoader
import io.github.nicolals.nostr.bench.resolveScenarioPath

fun main(args: Array<String>) {
    val cli = CliArgs(args)
    val scenarioPath = cli.get("scenario") ?: "scenarios/baseline.json"
    val scenario = ScenarioLoader.load(resolveScenarioPath(scenarioPath))

    val relayOverride = overrideRelayConfig(scenario.relay, cli)
    val server = RelaySimServer(scenario.copy(relay = relayOverride))

    println("[relay-sim] Starting relay on ws://${relayOverride.host}:${relayOverride.port}${relayOverride.path}")
    println("[relay-sim] Scenario: ${scenario.name}")

    server.start(wait = true)
}

private fun overrideRelayConfig(relay: RelayConfig, cli: CliArgs): RelayConfig {
    val host = cli.get("host") ?: relay.host
    val port = cli.getInt("port") ?: relay.port
    val path = cli.get("path") ?: relay.path
    return relay.copy(host = host, port = port, path = path)
}
