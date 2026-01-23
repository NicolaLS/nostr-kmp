package io.github.nicolals.nostr.bench

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

fun resolveScenarioPath(rawPath: String): Path {
    val direct = Path(rawPath)
    if (direct.isAbsolute) {
        require(direct.exists()) { "Scenario file not found: $direct" }
        return direct
    }

    val candidates = listOf(
        direct,
        Path("..").resolve(rawPath),
    )
    val found = candidates.firstOrNull { it.exists() }
    require(found != null) {
        "Scenario file not found. Tried: ${candidates.joinToString()}"
    }
    return found
}
