package io.github.nicolals.nostr.core.runtime

import io.github.nicolals.nostr.core.primitives.EventId

/**
 * Policy for deduplicating events within a session.
 *
 * When subscriptions are replaced or reconnections occur, the same event
 * may be received multiple times. This policy determines whether an event
 * has already been seen and should be filtered out.
 *
 * ## Thread Safety
 *
 * Built-in implementations are NOT thread-safe for simplicity and
 * portability. If you need thread-safe deduplication, either:
 * - Access the policy from a single thread/coroutine (recommended)
 * - Wrap calls with your own synchronization
 * - Provide a custom thread-safe implementation
 */
interface DeduplicationPolicy {

    /**
     * Checks if an event has been seen and should be deduplicated.
     *
     * If the event is new (not seen before), this method should record it
     * and return false. If the event has already been seen, return true.
     *
     * @param eventId The event ID to check.
     * @return True if the event is a duplicate (already seen), false if it's new.
     */
    fun isDuplicate(eventId: EventId): Boolean

    /**
     * Clears all recorded events.
     *
     * Called when the session is reset or closed.
     */
    fun clear()

    companion object {
        /**
         * Creates an LRU-based deduplication policy.
         *
         * Tracks the most recent [maxSize] event IDs. When the limit is reached,
         * the oldest (least recently seen) entry is evicted to make room.
         *
         * @param maxSize Maximum number of event IDs to track. Default is 10,000.
         */
        fun lru(maxSize: Int = 10_000): DeduplicationPolicy = LruDeduplicationPolicy(maxSize)

        /**
         * Creates a policy that never deduplicates.
         *
         * All events are considered new. Use this when deduplication is
         * handled at a higher layer or not needed.
         */
        fun none(): DeduplicationPolicy = NoDeduplicationPolicy
    }
}

/**
 * LRU-based deduplication policy.
 *
 * Tracks the most recent [maxSize] event IDs. Oldest entries are
 * evicted when the limit is reached.
 *
 * Uses a combination of a HashMap for O(1) lookup and a MutableList
 * for maintaining LRU order. When an existing event is accessed, it's
 * moved to the end of the list (most recently used).
 *
 * **Note:** This implementation is not thread-safe. Access should be
 * serialized by the caller (e.g., from a single coroutine).
 */
internal class LruDeduplicationPolicy(
    private val maxSize: Int,
) : DeduplicationPolicy {

    private class Node(
        val eventId: EventId,
        var prev: Node? = null,
        var next: Node? = null,
    )

    // Fast O(1) lookup of nodes.
    private val nodes = hashMapOf<EventId, Node>()

    // LRU order: head is oldest, tail is most recently used.
    private var head: Node? = null
    private var tail: Node? = null

    override fun isDuplicate(eventId: EventId): Boolean {
        if (maxSize <= 0) {
            return false
        }

        // Check if we've seen this event
        val existing = nodes[eventId]
        if (existing != null) {
            moveToTail(existing)
            return true
        }

        // Record new event
        val node = Node(eventId = eventId)
        appendToTail(node)
        nodes[eventId] = node

        // Evict oldest if over capacity
        if (nodes.size > maxSize) {
            evictOldest()
        }

        return false
    }

    override fun clear() {
        nodes.clear()
        head = null
        tail = null
    }

    private fun appendToTail(node: Node) {
        val currentTail = tail
        if (currentTail == null) {
            head = node
            tail = node
        } else {
            currentTail.next = node
            node.prev = currentTail
            tail = node
        }
    }

    private fun moveToTail(node: Node) {
        if (node === tail) return

        unlink(node)
        appendToTail(node)
    }

    private fun unlink(node: Node) {
        val prevNode = node.prev
        val nextNode = node.next

        if (prevNode != null) {
            prevNode.next = nextNode
        } else {
            head = nextNode
        }

        if (nextNode != null) {
            nextNode.prev = prevNode
        } else {
            tail = prevNode
        }

        node.prev = null
        node.next = null
    }

    private fun evictOldest() {
        val oldest = head ?: return
        unlink(oldest)
        nodes.remove(oldest.eventId)
    }
}

/**
 * Policy that never deduplicates.
 */
internal data object NoDeduplicationPolicy : DeduplicationPolicy {
    override fun isDuplicate(eventId: EventId): Boolean = false
    override fun clear() {}
}
