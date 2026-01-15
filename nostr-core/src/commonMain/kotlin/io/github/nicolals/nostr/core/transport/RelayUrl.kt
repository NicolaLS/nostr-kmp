package io.github.nicolals.nostr.core.transport

import kotlin.jvm.JvmInline

/**
 * A validated WebSocket URL for a Nostr relay.
 *
 * Ensures the URL uses the `ws://` or `wss://` scheme and performs
 * minimal normalization (lowercase scheme and host).
 *
 * @property value The normalized URL string.
 */
@JvmInline
value class RelayUrl private constructor(val value: String) {

    /**
     * The URL scheme, either "ws" or "wss".
     */
    val scheme: String
        get() = value.substringBefore("://")

    /**
     * Whether this URL uses a secure WebSocket connection (wss).
     */
    val isSecure: Boolean
        get() = scheme == "wss"

    /**
     * The host portion of the URL (lowercase).
     */
    val host: String
        get() = value.substringAfter("://").substringBefore("/").substringBefore(":")

    /**
     * The port number, or null if not explicitly specified.
     */
    val port: Int?
        get() {
            val afterHost = value.substringAfter("://").substringBefore("/")
            return if (":" in afterHost) {
                afterHost.substringAfter(":").toIntOrNull()
            } else {
                null
            }
        }

    /**
     * The path portion of the URL, or "/" if none specified.
     */
    val path: String
        get() {
            val afterScheme = value.substringAfter("://")
            return if ("/" in afterScheme) {
                "/" + afterScheme.substringAfter("/")
            } else {
                "/"
            }
        }

    override fun toString(): String = value

    companion object {
        private val VALID_SCHEMES = setOf("ws", "wss")

        /**
         * Creates a [RelayUrl] from the given URL string.
         *
         * @param url The WebSocket URL (must start with ws:// or wss://).
         * @return The validated and normalized [RelayUrl].
         * @throws IllegalArgumentException If the URL is invalid.
         */
        fun parse(url: String): RelayUrl {
            require(url.isNotBlank()) { "Relay URL must not be blank" }

            val schemeEnd = url.indexOf("://")
            require(schemeEnd > 0) { "Relay URL must contain a scheme (ws:// or wss://)" }

            val scheme = url.substring(0, schemeEnd).lowercase()
            require(scheme in VALID_SCHEMES) {
                "Relay URL must use ws:// or wss:// scheme, got: $scheme://"
            }

            val rest = url.substring(schemeEnd + 3)
            require(rest.isNotEmpty()) { "Relay URL must contain a host" }

            val hostPart = rest.substringBefore("/").substringBefore(":")
            require(hostPart.isNotEmpty()) { "Relay URL must contain a host" }

            // Normalize: lowercase scheme and host
            val normalizedHost = hostPart.lowercase()
            val afterHost = rest.substring(hostPart.length)
            val normalized = "$scheme://$normalizedHost$afterHost"

            return RelayUrl(normalized)
        }

        /**
         * Creates a [RelayUrl] from the given URL string, returning null if invalid.
         *
         * @param url The WebSocket URL to parse.
         * @return The validated [RelayUrl], or null if invalid.
         */
        fun parseOrNull(url: String): RelayUrl? = try {
            parse(url)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
