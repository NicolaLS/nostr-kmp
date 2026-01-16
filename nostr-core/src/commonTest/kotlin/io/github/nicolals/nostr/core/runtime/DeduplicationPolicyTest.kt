package io.github.nicolals.nostr.core.runtime

import io.github.nicolals.nostr.core.primitives.EventId
import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeduplicationPolicyTest {

    private fun eventId(n: Int): EventId {
        val hex = n.toString().padStart(64, '0')
        return EventId(hex.decodeHex())
    }

    @Test
    fun lruPolicyDetectsDuplicates() {
        val policy = DeduplicationPolicy.lru(maxSize = 100)

        val id1 = eventId(1)

        // First time - not a duplicate
        assertFalse(policy.isDuplicate(id1))

        // Second time - is a duplicate
        assertTrue(policy.isDuplicate(id1))

        // Third time - still a duplicate
        assertTrue(policy.isDuplicate(id1))
    }

    @Test
    fun lruPolicyTracksMultipleEvents() {
        val policy = DeduplicationPolicy.lru(maxSize = 100)

        val id1 = eventId(1)
        val id2 = eventId(2)
        val id3 = eventId(3)

        // First time for each - not duplicates
        assertFalse(policy.isDuplicate(id1))
        assertFalse(policy.isDuplicate(id2))
        assertFalse(policy.isDuplicate(id3))

        // Second time for each - duplicates
        assertTrue(policy.isDuplicate(id1))
        assertTrue(policy.isDuplicate(id2))
        assertTrue(policy.isDuplicate(id3))
    }

    @Test
    fun lruPolicyEvictsWhenFull() {
        val policy = DeduplicationPolicy.lru(maxSize = 3)

        val id1 = eventId(1)
        val id2 = eventId(2)
        val id3 = eventId(3)
        val id4 = eventId(4)
        val id5 = eventId(5)

        // Add 3 events (fills capacity)
        assertFalse(policy.isDuplicate(id1))
        assertFalse(policy.isDuplicate(id2))
        assertFalse(policy.isDuplicate(id3))

        // Add 4th event - triggers eviction of oldest (id1)
        assertFalse(policy.isDuplicate(id4))

        // Add 5th event - triggers eviction of oldest (id2)
        assertFalse(policy.isDuplicate(id5))

        // id1 and id2 should have been evicted
        // So they are not duplicates anymore (will be added fresh)
        assertFalse(policy.isDuplicate(id1))
        assertFalse(policy.isDuplicate(id2))
    }

    @Test
    fun lruPolicyLruUpdatesOnAccess() {
        val policy = DeduplicationPolicy.lru(maxSize = 3)

        val id1 = eventId(1)
        val id2 = eventId(2)
        val id3 = eventId(3)
        val id4 = eventId(4)

        // Add 3 events: order is [id1, id2, id3]
        assertFalse(policy.isDuplicate(id1))
        assertFalse(policy.isDuplicate(id2))
        assertFalse(policy.isDuplicate(id3))

        // Access id1 - moves it to most recently used: order is [id2, id3, id1]
        assertTrue(policy.isDuplicate(id1))

        // Add 4th event - id2 is evicted (now oldest): order is [id3, id1, id4]
        assertFalse(policy.isDuplicate(id4))

        // id2 was evicted, so it's not a duplicate
        assertFalse(policy.isDuplicate(id2))
    }

    @Test
    fun lruPolicyClearRemovesAllEntries() {
        val policy = DeduplicationPolicy.lru(maxSize = 100)

        val id1 = eventId(1)
        val id2 = eventId(2)

        // Add events
        assertFalse(policy.isDuplicate(id1))
        assertFalse(policy.isDuplicate(id2))

        // Clear
        policy.clear()

        // Events are no longer duplicates
        assertFalse(policy.isDuplicate(id1))
        assertFalse(policy.isDuplicate(id2))
    }

    @Test
    fun nonePolicyNeverDetectsDuplicates() {
        val policy = DeduplicationPolicy.none()

        val id1 = eventId(1)

        // Never reports duplicates
        assertFalse(policy.isDuplicate(id1))
        assertFalse(policy.isDuplicate(id1))
        assertFalse(policy.isDuplicate(id1))
    }

    @Test
    fun nonePolicyClearIsNoOp() {
        val policy = DeduplicationPolicy.none()

        // Clear doesn't throw
        policy.clear()
    }
}
