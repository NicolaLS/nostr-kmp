package io.github.nicolals.nostr.core.runtime

import io.github.nicolals.nostr.core.primitives.EventId
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RetryPolicyTest {

    private val testEventId = EventId("0".repeat(64).decodeHex())
    private val testSubId = SubscriptionId("test-sub")

    @Test
    fun defaultPolicyRetriesPublishWithExponentialBackoff() {
        val policy = RetryPolicy.default()

        // First retry: 500ms
        val decision1 = policy.shouldRetryPublish(testEventId, 1)
        assertIs<RetryDecision.Retry>(decision1)
        assertEquals(500, decision1.delayMs)

        // Second retry: 1000ms
        val decision2 = policy.shouldRetryPublish(testEventId, 2)
        assertIs<RetryDecision.Retry>(decision2)
        assertEquals(1000, decision2.delayMs)

        // Third retry: 2000ms
        val decision3 = policy.shouldRetryPublish(testEventId, 3)
        assertIs<RetryDecision.Retry>(decision3)
        assertEquals(2000, decision3.delayMs)
    }

    @Test
    fun defaultPolicyAbandonsAfterMaxRetries() {
        val policy = RetryPolicy.default()

        // Attempts 1-3 should retry
        assertIs<RetryDecision.Retry>(policy.shouldRetryPublish(testEventId, 1))
        assertIs<RetryDecision.Retry>(policy.shouldRetryPublish(testEventId, 2))
        assertIs<RetryDecision.Retry>(policy.shouldRetryPublish(testEventId, 3))

        // Attempt 4 should abandon
        val decision4 = policy.shouldRetryPublish(testEventId, 4)
        assertIs<RetryDecision.Abandon>(decision4)
    }

    @Test
    fun defaultPolicyResubscribesAfterDisconnect() {
        val policy = RetryPolicy.default()

        val decision1 = policy.shouldResubscribe(testSubId, 1, SubscriptionRetryReason.Disconnected)
        val decision2 = policy.shouldResubscribe(SubscriptionId("another-sub"), 2, SubscriptionRetryReason.Disconnected)

        assertIs<RetryDecision.Retry>(decision1)
        assertIs<RetryDecision.Retry>(decision2)
    }

    @Test
    fun defaultPolicyDoesNotResubscribeAfterRelayClosed() {
        val policy = RetryPolicy.default()

        val decision = policy.shouldResubscribe(testSubId, 1, SubscriptionRetryReason.Closed("rate-limited"))
        assertIs<RetryDecision.Abandon>(decision)
    }

    @Test
    fun neverPolicyNeverRetries() {
        val policy = RetryPolicy.never()

        assertIs<RetryDecision.Abandon>(policy.shouldRetryPublish(testEventId, 1))
        assertIs<RetryDecision.Abandon>(policy.shouldRetryPublish(testEventId, 100))
    }

    @Test
    fun neverPolicyNeverResubscribes() {
        val policy = RetryPolicy.never()

        assertIs<RetryDecision.Abandon>(
            policy.shouldResubscribe(testSubId, 1, SubscriptionRetryReason.Disconnected)
        )
    }

    @Test
    fun alwaysPolicyAlwaysRetries() {
        val policy = RetryPolicy.always(publishRetryDelayMs = 500)

        // Should always retry with specified delay
        for (attempt in 1..100) {
            val decision = policy.shouldRetryPublish(testEventId, attempt)
            assertIs<RetryDecision.Retry>(decision)
            assertEquals(500, decision.delayMs)
        }
    }

    @Test
    fun alwaysPolicyAlwaysResubscribes() {
        val policy = RetryPolicy.always()

        val decision = policy.shouldResubscribe(testSubId, 1, SubscriptionRetryReason.Disconnected)
        assertIs<RetryDecision.Retry>(decision)
    }
}
