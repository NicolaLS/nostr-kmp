package io.github.nicolals.nostr.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReconnectPolicyTest {

    @Test
    fun exponentialBackoffReconnectsWithIncreasingDelay() {
        val policy = ReconnectPolicy.exponentialBackoff(
            maxAttempts = 5,
            baseDelayMs = 1000,
            maxDelayMs = 30_000,
            jitterFactor = 0.0, // Disable jitter for deterministic test
        )

        // First attempt: ~1000ms
        val decision1 = policy.shouldReconnect(1, null)
        assertIs<ReconnectDecision.Reconnect>(decision1)
        assertEquals(1000, decision1.delayMs)

        // Second attempt: ~2000ms
        val decision2 = policy.shouldReconnect(2, null)
        assertIs<ReconnectDecision.Reconnect>(decision2)
        assertEquals(2000, decision2.delayMs)

        // Third attempt: ~4000ms
        val decision3 = policy.shouldReconnect(3, null)
        assertIs<ReconnectDecision.Reconnect>(decision3)
        assertEquals(4000, decision3.delayMs)
    }

    @Test
    fun exponentialBackoffCapsAtMaxDelay() {
        val policy = ReconnectPolicy.exponentialBackoff(
            maxAttempts = 10,
            baseDelayMs = 1000,
            maxDelayMs = 5000,
            jitterFactor = 0.0,
        )

        // At attempt 3: 1000 * 4 = 4000
        val decision3 = policy.shouldReconnect(3, null)
        assertIs<ReconnectDecision.Reconnect>(decision3)
        assertEquals(4000, decision3.delayMs)

        // At attempt 4: 1000 * 8 = 8000, capped to 5000
        val decision4 = policy.shouldReconnect(4, null)
        assertIs<ReconnectDecision.Reconnect>(decision4)
        assertEquals(5000, decision4.delayMs)

        // At attempt 5: still capped at 5000
        val decision5 = policy.shouldReconnect(5, null)
        assertIs<ReconnectDecision.Reconnect>(decision5)
        assertEquals(5000, decision5.delayMs)
    }

    @Test
    fun exponentialBackoffGivesUpAfterMaxAttempts() {
        val policy = ReconnectPolicy.exponentialBackoff(
            maxAttempts = 3,
            baseDelayMs = 1000,
            maxDelayMs = 30_000,
            jitterFactor = 0.0,
        )

        // Attempts 1-3 should reconnect
        assertIs<ReconnectDecision.Reconnect>(policy.shouldReconnect(1, null))
        assertIs<ReconnectDecision.Reconnect>(policy.shouldReconnect(2, null))
        assertIs<ReconnectDecision.Reconnect>(policy.shouldReconnect(3, null))

        // Attempt 4 should give up
        val decision4 = policy.shouldReconnect(4, null)
        assertIs<ReconnectDecision.GiveUp>(decision4)
    }

    @Test
    fun exponentialBackoffAddsJitter() {
        val policy = ReconnectPolicy.exponentialBackoff(
            maxAttempts = 5,
            baseDelayMs = 1000,
            maxDelayMs = 30_000,
            jitterFactor = 0.1, // 10% jitter
        )

        // Run multiple times to check jitter is applied
        val delays = (1..100).map {
            val decision = policy.shouldReconnect(1, null)
            assertIs<ReconnectDecision.Reconnect>(decision)
            decision.delayMs
        }

        // With 10% jitter on 1000ms base, delays should be in range [1000, 1100]
        assertTrue(delays.all { it in 1000..1100 })
        // And there should be variation (not all same)
        assertTrue(delays.distinct().size > 1)
    }

    @Test
    fun neverPolicyAlwaysGivesUp() {
        val policy = ReconnectPolicy.never()

        assertIs<ReconnectDecision.GiveUp>(policy.shouldReconnect(1, null))
        assertIs<ReconnectDecision.GiveUp>(policy.shouldReconnect(2, null))
        assertIs<ReconnectDecision.GiveUp>(policy.shouldReconnect(100, null))
    }
}
