package io.github.nicolals.nwc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResponseCorrelationPolicyTest {
    @Test
    fun parallelOutOfOrderResponsesOnlyMatchTheirExactRequest() {
        assertTrue(
            isExactResponseMatch(
                responseRequestId = "request-b",
                pendingRequestId = "request-b",
                expectedMethod = "lookup_invoice",
                resultType = "lookup_invoice"
            )
        )
        assertFalse(
            isExactResponseMatch(
                responseRequestId = "request-b",
                pendingRequestId = "request-a",
                expectedMethod = "pay_invoice",
                resultType = "lookup_invoice"
            )
        )
    }

    @Test
    fun untaggedAndMismatchedResponsesAreRejected() {
        assertFalse(
            isExactResponseMatch(
                responseRequestId = null,
                pendingRequestId = "request-a",
                expectedMethod = "pay_invoice",
                resultType = "pay_invoice"
            )
        )
        assertFalse(
            isExactResponseMatch(
                responseRequestId = "request-a",
                pendingRequestId = "request-a",
                expectedMethod = "pay_invoice",
                resultType = "lookup_invoice"
            )
        )
    }

    @Test
    fun expirationRoundsUpAndIncludesFiveSecondClockSkew() {
        assertEquals(1_006L, requestExpirationSeconds(nowSeconds = 1_000L, timeoutMs = 1L))
        assertEquals(1_020L, requestExpirationSeconds(nowSeconds = 1_000L, timeoutMs = 15_000L))
    }
    @Test
    fun expirationRejectsNonpositiveTimeoutAndSaturatesOverflow() {
        assertFailsWith<IllegalArgumentException> { requestExpirationSeconds(1_000, 0) }
        assertFailsWith<IllegalArgumentException> { requestExpirationSeconds(1_000, -1) }
        assertEquals(9_223_372_036_855_781L, requestExpirationSeconds(1_000, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, requestExpirationSeconds(Long.MAX_VALUE - 1, 1_000))
    }

}
