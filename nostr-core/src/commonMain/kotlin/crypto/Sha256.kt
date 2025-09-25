package nostr.core.crypto

/**
 * Minimal SHA-256 abstraction. `nostr-core` relies on hashing in exactly one place—the
 * canonical event array defined by NIP-01—so the interface is intentionally tiny. The
 * default implementation delegates to the platform `MessageDigest` (see expect/actual
 * declarations), but embedders can supply alternate implementations for testing or
 * hardware accelerated backends.
 */
fun interface Sha256 {
    /** Returns the 32-byte SHA-256 digest for the given [bytes]. */
    fun hash(bytes: ByteArray): ByteArray

    companion object {
        /** Default implementation backed by the platform-specific [sha256] expect/actual. */
        val Default: Sha256 = Sha256 { bytes -> sha256(bytes) }
    }
}

/**
 * Platform-specific implementation used by [Sha256.Default]. Each target provides a
 * straightforward wrapper around its native SHA-256 API so that common code stays free
 * of platform dependencies.
 */
expect fun sha256(input: ByteArray): ByteArray
