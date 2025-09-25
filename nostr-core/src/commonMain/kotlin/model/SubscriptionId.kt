package nostr.core.model

/**
 * Subscription identifiers per NIP-01: arbitrary non-empty UTF-8 strings with a maximum length
 * of 64 characters. Using a value class ensures each identifier is validated exactly once before
 * it flows through higher-level components.
 */
@JvmInline
value class SubscriptionId(val value: String) {
    init {
        require(value.isNotEmpty()) { "subscription id must not be empty" }
        require(value.length <= MAX_LENGTH) { "subscription id must be at most $MAX_LENGTH characters" }
    }

    override fun toString(): String = value

    companion object {
        private const val MAX_LENGTH: Int = 64
    }
}

/** Converts this string into a [SubscriptionId], enforcing the NIP-01 length limits. */
fun String.toSubscriptionId(): SubscriptionId = SubscriptionId(this)
