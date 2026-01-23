package io.github.nicolals.nostr.bench

import kotlin.random.Random

/**
 * Deterministic payload generator shared by relay and client benchmarks.
 */
class EventPayloadFactory(
    private val seed: Long,
    private val profiles: Map<String, EventProfile>,
) {
    fun payload(profileName: String, seq: Long, phase: String, offsetNs: Long): EventPayload {
        val profile = profiles[profileName]
            ?: error("Event profile '$profileName' is not defined.")

        val random = Random(mixSeed(seed, profileName, seq))
        val content = randomAscii(profile.contentSize, random)
        val tags = buildList {
            repeat(profile.tagsPerEvent) { index ->
                val tagKey = when (random.nextInt(3)) {
                    0 -> "e"
                    1 -> "p"
                    else -> "t"
                }
                val value = randomHex(profile.tagValueSize, random)
                add(listOf(tagKey, value, "v$index"))
            }
            addAll(BenchTags.benchTags(phase, seq, offsetNs))
        }

        return EventPayload(
            kind = profile.kind,
            content = content,
            tags = tags,
        )
    }

    private fun mixSeed(base: Long, profile: String, seq: Long): Long {
        val profileHash = profile.hashCode().toLong()
        return base xor (profileHash shl 32) xor seq
    }

    private fun randomAscii(size: Int, random: Random): String {
        if (size <= 0) return ""
        val chars = CharArray(size)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        for (i in 0 until size) {
            chars[i] = alphabet[random.nextInt(alphabet.length)]
        }
        return chars.concatToString()
    }

    private fun randomHex(length: Int, random: Random): String {
        if (length <= 0) return ""
        val chars = CharArray(length)
        val alphabet = "0123456789abcdef"
        for (i in 0 until length) {
            chars[i] = alphabet[random.nextInt(alphabet.length)]
        }
        return chars.concatToString()
    }
}

data class EventPayload(
    val kind: Int,
    val content: String,
    val tags: List<List<String>>,
)
