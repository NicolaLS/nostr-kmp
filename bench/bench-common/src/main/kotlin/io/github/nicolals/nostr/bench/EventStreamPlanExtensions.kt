package io.github.nicolals.nostr.bench

import kotlin.math.ceil

fun EventStreamPlan.resolvedCount(): Int {
    return when {
        count != null -> count
        durationMs != null && ratePerSec != null -> {
            val durationSec = durationMs.toDouble() / 1000.0
            ceil(durationSec * ratePerSec).toInt()
        }
        else -> 0
    }
}

fun EventStreamPlan.intervalNs(): Long {
    val rate = ratePerSec ?: return 0L
    if (rate <= 0) return 0L
    return 1_000_000_000L / rate
}
