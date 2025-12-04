package io.github.nicolals.nostr.client

import nostr.core.crypto.Signer

/**
 * Configuration for [NostrClient].
 *
 * Most users should use one of the factory methods on [NostrClient] instead of
 * creating this directly:
 * - [NostrClient.create] with a signer
 * - [NostrClient.create] without arguments (generates random keypair)
 * - [NostrClient.ephemeral] (new keypair per publish)
 *
 * @property signer The signer for authentication and publishing. If null, a random
 *                  keypair will be generated.
 * @property ephemeralKeys If true, generates a new random keypair for every publish
 *                         operation. Provides maximum privacy by preventing event correlation.
 * @property defaultTimeoutMillis Default timeout for operations (query, publish, request).
 * @property connectTimeoutMillis Timeout for establishing connections.
 * @property maxRetries Maximum number of retry attempts for transient failures.
 */
data class NostrClientConfig(
    val signer: Signer? = null,
    val ephemeralKeys: Boolean = false,
    val defaultTimeoutMillis: Long = 30_000,
    val connectTimeoutMillis: Long = 10_000,
    val maxRetries: Int = 2
) {
    init {
        require(defaultTimeoutMillis > 0) { "defaultTimeoutMillis must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
        require(!ephemeralKeys || signer == null) {
            "Cannot specify both signer and ephemeralKeys=true"
        }
    }
}
