package io.github.nicolals.nostr.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class CloseReasonTest {
    @Test fun parsesKnownPrefixesAndPreservesOriginalRelayText() {
        val auth = "  auth-required:  Authenticate first"
        val restricted = "restricted: Not permitted"
        assertEquals(CloseReason.AuthRequired("Authenticate first", auth), CloseReason.parse(auth))
        assertEquals(CloseReason.Restricted("Not permitted", restricted), CloseReason.parse(restricted))
        assertEquals(CloseReason.Other("error: unknown"), CloseReason.parse("error: unknown"))
    }
}
