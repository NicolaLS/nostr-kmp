package io.github.nicolals.nostr.bench.relay

import io.github.nicolals.nostr.bench.EventPayloadFactory
import io.github.nicolals.nostr.bench.EventProfile
import io.github.nicolals.nostr.bench.EventStreamPlan
import io.github.nicolals.nostr.bench.resolvedCount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

class EventJsonFactory(
    private val seed: Long,
    private val profiles: Map<String, EventProfile>,
    private val payloadFactory: EventPayloadFactory,
    private val baseCreatedAt: Long,
) {
    private val json = Json { encodeDefaults = false }
    private val pubkeys = mutableMapOf<String, String>()

    fun eventsFor(plan: EventStreamPlan, phase: String): List<String> {
        val count = plan.resolvedCount()
        if (count <= 0) return emptyList()

        val intervalNs = planIntervalNs(plan)
        val events = ArrayList<String>(count)
        for (i in 0 until count) {
            val offsetNs = intervalNs * i.toLong()
            val seq = i.toLong()
            events += eventJson(plan.profile, seq, phase, offsetNs)
        }
        return events
    }

    fun eventJson(profileName: String, seq: Long, phase: String, offsetNs: Long): String {
        val profile = profiles[profileName]
            ?: error("Event profile '$profileName' is not defined.")

        val payload = payloadFactory.payload(profileName, seq, phase, offsetNs)
        val createdAt = baseCreatedAt + (seq % 600)
        val pubkey = pubkeys.getOrPut(profileName) {
            profile.pubkeyHex ?: randomHex(32, Random(mixSeed(seed, profileName, 1)))
        }

        val id = randomHex(32, Random(mixSeed(seed, profileName, seq)))
        val sig = randomHex(64, Random(mixSeed(seed, profileName, seq xor GOLDEN_GAMMA)))

        val jsonEvent = buildJsonObject {
            put("id", id)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("kind", payload.kind)
            put("tags", buildJsonArray {
                payload.tags.forEach { tag ->
                    add(buildJsonArray {
                        tag.forEach { value -> add(JsonPrimitive(value)) }
                    })
                }
            })
            put("content", payload.content)
            put("sig", sig)
        }

        return json.encodeToString(jsonEvent)
    }

    private fun planIntervalNs(plan: EventStreamPlan): Long {
        val rate = plan.ratePerSec ?: return 0L
        if (rate <= 0) return 0L
        return 1_000_000_000L / rate
    }

    private fun mixSeed(base: Long, profile: String, seq: Long): Long {
        val profileHash = profile.hashCode().toLong()
        return base xor (profileHash shl 32) xor seq
    }

    private fun randomHex(bytes: Int, random: Random): String {
        val alphabet = "0123456789abcdef"
        val chars = CharArray(bytes * 2)
        for (i in chars.indices) {
            chars[i] = alphabet[random.nextInt(alphabet.length)]
        }
        return chars.concatToString()
    }

    private companion object {
        // 64-bit golden ratio constant wrapped into signed long range.
        private const val GOLDEN_GAMMA: Long = -0x61C8864680B583EBL
    }
}
