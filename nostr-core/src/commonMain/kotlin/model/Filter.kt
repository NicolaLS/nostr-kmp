package nostr.core.model

import nostr.core.utils.isLowercaseHex

/**
 * Represents a NIP-01 subscription filter. All validation occurs eagerly so filters that
 * reach the wire are guaranteed to respect the base protocol (hex lengths, kind ranges,
 * tag formatting). Tag keys follow the NIP convention of `#` + single letter.
 */
data class Filter(
    val ids: Set<String>? = null,
    val authors: Set<String>? = null,
    val kinds: Set<Int>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val tags: Map<String, Set<String>> = emptyMap()
) {
    init {
        ids?.let { values ->
            require(values.isNotEmpty()) { "ids filter must contain at least one value" }
            values.forEach { id ->
                require(id.isLowercaseHex(64)) { "each id must be 64 lowercase hex characters" }
            }
        }
        authors?.let { values ->
            require(values.isNotEmpty()) { "authors filter must contain at least one value" }
            values.forEach { author ->
                require(author.isLowercaseHex(64)) { "each author must be 64 lowercase hex characters" }
            }
        }
        kinds?.let { values ->
            require(values.isNotEmpty()) { "kinds filter must contain at least one value" }
            values.forEach { kind ->
                require(kind in 0..Event.KIND_MAX) { "filter kind must be between 0 and ${Event.KIND_MAX}" }
            }
        }
        tags.keys.forEach { key ->
            require(key.startsWith("#")) { "Filter tag keys must start with '#' (was '$key')" }
            val suffix = key.drop(1)
            require(suffix.length == 1 && suffix[0].isLetter()) {
                "Filter tag key must be '#<letter>' (was '$key')"
            }
            val values = tags.getValue(key)
            require(values.isNotEmpty()) { "Filter tag '$key' must contain at least one value" }
            values.forEach { value ->
                require(value.isNotEmpty()) { "Filter tag '$key' values cannot be empty strings" }
            }
        }
    }

    fun withTags(updater: (MutableMap<String, Set<String>>) -> Unit): Filter {
        val mutable = tags.toMutableMap()
        updater(mutable)
        return copy(tags = mutable.toMap())
    }
}
