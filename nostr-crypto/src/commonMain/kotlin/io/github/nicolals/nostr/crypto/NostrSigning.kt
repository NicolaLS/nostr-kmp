package io.github.nicolals.nostr.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import fr.acinq.secp256k1.Secp256k1
import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.crypto.SigningPrimitives
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Default implementation of [SigningPrimitives] using secp256k1-kmp and libsodium.
 *
 * This object provides all signing-related cryptographic operations needed for Nostr:
 * - Key generation and validation
 * - Public key derivation
 * - BIP-340 Schnorr signatures
 *
 * ## Thread Safety
 *
 * All operations are thread-safe. Libsodium is initialized lazily on first use.
 *
 * ## Usage
 *
 * ```kotlin
 * // Generate a new keypair
 * val secretKey = NostrSigning.generateSecretKey().getOrThrow()
 * val publicKey = NostrSigning.derivePublicKeyXOnly(secretKey).getOrThrow()
 *
 * // Sign a message
 * val signature = NostrSigning.schnorrSign(secretKey, messageHash).getOrThrow()
 *
 * // Verify a signature
 * val valid = NostrSigning.schnorrVerify(signature, messageHash, publicKey).getOrThrow()
 * ```
 */
object NostrSigning : SigningPrimitives {

    override fun generateSecretKey(): CryptoResult<ByteString> {
        return withSodium("generateSecretKey") {
            // Generate random bytes until we get a valid secp256k1 secret key
            // Most random 32-byte values are valid, but we verify to be safe
            var attempts = 0
            val maxAttempts = 100
            while (attempts < maxAttempts) {
                @OptIn(ExperimentalUnsignedTypes::class)
                val candidate = LibsodiumRandom.buf(32).toByteArray()
                if (Secp256k1.secKeyVerify(candidate)) {
                    return@withSodium candidate.toByteString()
                }
                attempts++
            }
            throw IllegalStateException("Failed to generate valid secret key after $maxAttempts attempts")
        }
    }

    override fun verifySecretKey(secretKey32: ByteString): CryptoResult<Boolean> {
        if (secretKey32.size != 32) {
            return CryptoResult.Err(
                CryptoError.InvalidPrivateKey("expected 32 bytes, got ${secretKey32.size}")
            )
        }
        return runProvider("verifySecretKey") {
            Secp256k1.secKeyVerify(secretKey32.toByteArray())
        }
    }

    override fun derivePublicKeyXOnly(secretKey32: ByteString): CryptoResult<ByteString> {
        if (secretKey32.size != 32) {
            return CryptoResult.Err(
                CryptoError.InvalidPrivateKey("expected 32 bytes, got ${secretKey32.size}")
            )
        }
        return runProvider("derivePublicKeyXOnly") {
            val pubKey = Secp256k1.pubkeyCreate(secretKey32.toByteArray())
            // pubkeyCreate returns compressed format (33 bytes with 02/03 prefix)
            // We need x-only format (32 bytes without prefix)
            if (pubKey.size != 33) {
                throw IllegalStateException("unexpected public key size: ${pubKey.size}")
            }
            // Drop the first byte (parity prefix) to get x-only
            pubKey.copyOfRange(1, 33).toByteString()
        }
    }

    override fun schnorrSign(
        secretKey32: ByteString,
        message32: ByteString,
        auxRand32: ByteString?,
    ): CryptoResult<ByteString> {
        if (secretKey32.size != 32) {
            return CryptoResult.Err(
                CryptoError.InvalidPrivateKey("expected 32 bytes, got ${secretKey32.size}")
            )
        }
        if (message32.size != 32) {
            return CryptoResult.Err(
                CryptoError.InvalidInput("message32", "32 bytes", message32.size)
            )
        }
        val auxRand = auxRand32?.toByteArray()
        if (auxRand != null && auxRand.size != 32) {
            return CryptoResult.Err(
                CryptoError.InvalidInput("auxRand32", "32 bytes or null", auxRand.size)
            )
        }

        return withSodium("schnorrSign") {
            @OptIn(ExperimentalUnsignedTypes::class)
            val actualAuxRand = auxRand ?: LibsodiumRandom.buf(32).toByteArray()
            try {
                val sig = Secp256k1.signSchnorr(
                    message32.toByteArray(),
                    secretKey32.toByteArray(),
                    actualAuxRand
                )
                sig.toByteString()
            } catch (e: Exception) {
                throw RuntimeException("Schnorr signing failed: ${e.message}", e)
            }
        }
    }

    override fun schnorrVerify(
        signature64: ByteString,
        message32: ByteString,
        publicKey32: ByteString,
    ): CryptoResult<Boolean> {
        if (signature64.size != 64) {
            return CryptoResult.Err(
                CryptoError.InvalidInput("signature64", "64 bytes", signature64.size)
            )
        }
        if (message32.size != 32) {
            return CryptoResult.Err(
                CryptoError.InvalidInput("message32", "32 bytes", message32.size)
            )
        }
        if (publicKey32.size != 32) {
            return CryptoResult.Err(
                CryptoError.InvalidPublicKey("expected 32 bytes, got ${publicKey32.size}")
            )
        }
        return runProvider("schnorrVerify") {
            Secp256k1.verifySchnorr(
                signature64.toByteArray(),
                message32.toByteArray(),
                publicKey32.toByteArray()
            )
        }
    }

    // ---- Internal helpers ----

    private fun ensureSodium(): CryptoResult<Unit> {
        if (LibsodiumInitializer.isInitialized()) return CryptoResult.Ok(Unit)
        return try {
            LibsodiumInitializer.initializeWithCallback {}
            if (LibsodiumInitializer.isInitialized()) {
                CryptoResult.Ok(Unit)
            } else {
                CryptoResult.Err(CryptoError.InitializationFailed("libsodium"))
            }
        } catch (e: Exception) {
            CryptoResult.Err(CryptoError.InitializationFailed("libsodium", e.message))
        }
    }

    private inline fun <T> withSodium(operation: String, block: () -> T): CryptoResult<T> {
        return when (val init = ensureSodium()) {
            is CryptoResult.Err -> CryptoResult.Err(init.error)
            is CryptoResult.Ok -> runProvider(operation, block)
        }
    }

    private inline fun <T> runProvider(operation: String, block: () -> T): CryptoResult<T> =
        try {
            CryptoResult.Ok(block())
        } catch (e: Exception) {
            CryptoResult.Err(CryptoError.ProviderError(operation, e.message))
        }
}
