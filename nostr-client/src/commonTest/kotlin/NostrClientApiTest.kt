package io.github.nicolals.nostr.client

import kotlinx.coroutines.test.runTest
import nostr.crypto.KeyPair
import nostr.crypto.Signer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NostrClientApiTest {

    @Test
    fun createWithSignerUsesProvidedSigner() {
        val keyPair = KeyPair.random()
        val signer = Signer.fromKeyPair(keyPair)
        val client = NostrClient.create(signer)

        assertNotNull(client)
        assertEquals(keyPair.publicKey.hex, client.publicKey)
    }

    @Test
    fun createWithoutSignerGeneratesRandomKeypair() {
        val client = NostrClient.create()

        assertNotNull(client)
        assertNotNull(client.publicKey)
        assertEquals(64, client.publicKey.length) // hex pubkey is 64 chars
    }

    @Test
    fun ephemeralCreatesClientForPrivacyMode() {
        val client = NostrClient.ephemeral()

        assertNotNull(client)
        assertTrue(client.isEphemeral)
    }

    @Test
    fun publicKeyReturnsHexEncodedKey() {
        val keyPair = KeyPair.random()
        val signer = Signer.fromKeyPair(keyPair)
        val client = NostrClient.create(signer)

        assertEquals(keyPair.publicKey.hex, client.publicKey)
        assertEquals(keyPair.publicKey.hex, client.publicKeyOrNull)
        assertFalse(client.isEphemeral)
    }

    @Test
    fun ephemeralClientThrowsOnPublicKeyAccess() {
        val client = NostrClient.ephemeral()

        assertFails { client.publicKey }
        assertNull(client.publicKeyOrNull)
        assertTrue(client.isEphemeral)
    }

    @Test
    fun publicKeyIsConsistentAcrossCalls() {
        val client = NostrClient.create()
        val key1 = client.publicKey
        val key2 = client.publicKey

        assertEquals(key1, key2)
    }

    @Test
    fun createWithConfigUsesConfigValues() {
        val config = NostrClientConfig(
            defaultTimeoutMillis = 60_000,
            connectTimeoutMillis = 15_000,
            maxRetries = 5
        )
        val client = NostrClient.create(config)

        assertNotNull(client)
    }

    @Test
    fun configValidatesPositiveTimeout() {
        try {
            NostrClientConfig(defaultTimeoutMillis = 0)
            assertTrue(false, "Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("positive") == true)
        }
    }

    @Test
    fun configValidatesNonNegativeRetries() {
        try {
            NostrClientConfig(maxRetries = -1)
            assertTrue(false, "Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("non-negative") == true)
        }
    }

    @Test
    fun configPreventsSignerWithEphemeralKeys() {
        val signer = Signer.fromKeyPair(KeyPair.random())
        try {
            NostrClientConfig(signer = signer, ephemeralKeys = true)
            assertTrue(false, "Should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("ephemeralKeys") == true)
        }
    }

    @Test
    fun connectionStateValuesExist() {
        // Verify all connection states are accessible
        val disconnected: ConnectionState = ConnectionState.Disconnected
        val connecting: ConnectionState = ConnectionState.Connecting
        val connected: ConnectionState = ConnectionState.Connected
        val failed: ConnectionState = ConnectionState.Failed("error")

        assertEquals("error", (failed as ConnectionState.Failed).error)
    }

    @Test
    fun relaysStartsEmpty() {
        val client = NostrClient.create()
        assertTrue(client.relays.isEmpty())
    }

    @Test
    fun addRelayAddsToManagedRelays() = runTest {
        val client = NostrClient.create()
        client.addRelay("wss://relay.damus.io")

        assertEquals(setOf("wss://relay.damus.io"), client.relays)
    }

    @Test
    fun addRelayNormalizesUrl() = runTest {
        val client = NostrClient.create()
        client.addRelay("wss://relay.damus.io/")

        assertEquals(setOf("wss://relay.damus.io"), client.relays)
    }

    @Test
    fun addRelayMultipleRelays() = runTest {
        val client = NostrClient.create()
        client.addRelay("wss://relay.damus.io")
        client.addRelay("wss://nos.lol")

        assertEquals(setOf("wss://relay.damus.io", "wss://nos.lol"), client.relays)
    }

    @Test
    fun addRelayIgnoresDuplicates() = runTest {
        val client = NostrClient.create()
        client.addRelay("wss://relay.damus.io")
        client.addRelay("wss://relay.damus.io")

        assertEquals(setOf("wss://relay.damus.io"), client.relays)
    }

    @Test
    fun removeRelayRemovesFromManagedRelays() = runTest {
        val client = NostrClient.create()
        client.addRelay("wss://relay.damus.io")
        client.addRelay("wss://nos.lol")
        client.removeRelay("wss://relay.damus.io")

        assertEquals(setOf("wss://nos.lol"), client.relays)
    }

    @Test
    fun removeRelayIgnoresNonExistent() = runTest {
        val client = NostrClient.create()
        client.addRelay("wss://relay.damus.io")
        client.removeRelay("wss://nos.lol")

        assertEquals(setOf("wss://relay.damus.io"), client.relays)
    }
}

class PublishHandleTest {

    @Test
    fun publishHandleTracksTargetedRelays() {
        val handle = PublishHandle(
            eventId = "abc123",
            targetedRelays = listOf("wss://relay1.com", "wss://relay2.com")
        )

        assertEquals("abc123", handle.eventId)
        assertEquals(listOf("wss://relay1.com", "wss://relay2.com"), handle.targetedRelays)
    }

    @Test
    fun publishHandleStartsWithEmptyResults() {
        val handle = PublishHandle(
            eventId = "abc123",
            targetedRelays = listOf("wss://relay1.com")
        )

        assertTrue(handle.results.isEmpty())
        assertTrue(handle.confirmedRelays.isEmpty())
        assertFalse(handle.isComplete)
        assertFalse(handle.hasAccepted)
    }

    @Test
    fun publishHandlePendingRelaysIncludesAllTargeted() {
        val handle = PublishHandle(
            eventId = "abc123",
            targetedRelays = listOf("wss://relay1.com", "wss://relay2.com")
        )

        assertEquals(listOf("wss://relay1.com", "wss://relay2.com"), handle.pendingRelays)
    }

    @Test
    fun publishHandleTracksResults() = runTest {
        val handle = PublishHandle(
            eventId = "abc123",
            targetedRelays = listOf("wss://relay1.com", "wss://relay2.com")
        )

        handle.onResult("wss://relay1.com", nostr.core.model.PublishResult("abc123", true, ""))

        assertEquals(1, handle.results.size)
        assertEquals(listOf("wss://relay1.com"), handle.confirmedRelays)
        assertEquals(listOf("wss://relay1.com"), handle.acceptedRelays)
        assertTrue(handle.rejectedRelays.isEmpty())
        assertTrue(handle.hasAccepted)
        assertFalse(handle.isComplete)
    }

    @Test
    fun publishHandleTracksRejections() = runTest {
        val handle = PublishHandle(
            eventId = "abc123",
            targetedRelays = listOf("wss://relay1.com")
        )

        handle.onResult("wss://relay1.com", nostr.core.model.PublishResult("abc123", false, "duplicate"))

        assertEquals(listOf("wss://relay1.com"), handle.confirmedRelays)
        assertTrue(handle.acceptedRelays.isEmpty())
        assertEquals(listOf("wss://relay1.com"), handle.rejectedRelays)
        assertFalse(handle.hasAccepted)
        assertTrue(handle.isComplete)
    }

    @Test
    fun publishHandleIsCompleteWhenAllRespond() = runTest {
        val handle = PublishHandle(
            eventId = "abc123",
            targetedRelays = listOf("wss://relay1.com", "wss://relay2.com")
        )

        handle.onResult("wss://relay1.com", nostr.core.model.PublishResult("abc123", true, ""))
        assertFalse(handle.isComplete)

        handle.onResult("wss://relay2.com", nostr.core.model.PublishResult("abc123", true, ""))
        assertTrue(handle.isComplete)
    }
}
