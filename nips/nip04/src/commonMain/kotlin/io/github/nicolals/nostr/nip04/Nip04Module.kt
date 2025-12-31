package io.github.nicolals.nostr.nip04

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.nip04.event.EncryptedDirectMessageEventUpgrader
import io.github.nicolals.nostr.nip04.model.Nip04CipherText
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * This module provides the [io.github.nicolals.nostr.nip04.event.EncryptedDirectMessageEvent] view
 * with encryption/decryption helpers for nip-04 events.
 */
class Nip04Module(
    private val ctx: NipModuleContext,
) : NipModule {
    override val eventUpgraders: List<EventUpgrader<out EventView>> =
        listOf(EncryptedDirectMessageEventUpgrader(ctx))
}

fun NipModuleContext.parseContentOrNull(content: String): Nip04CipherText? =
    Nip04CipherText.parseOrNull(content)

fun NipModuleContext.encryptNip04(
    plaintext: String,
    senderPrivateKey: ByteString,
    recipientPublicKey: PublicKey,
): CryptoResult<Nip04CipherText> {
    val plaintextBytes = plaintext.encodeToByteArray().toByteString()
    return crypto.ecdhXOnly(senderPrivateKey, recipientPublicKey.bytes).flatMap { sharedSecret ->
        crypto.randomBytes(16).flatMap { iv ->
            crypto.aes256CbcEncrypt(sharedSecret, iv, plaintextBytes).flatMap { cipherBytes ->
                crypto.base64Encode(cipherBytes).flatMap { cipherBase64 ->
                    crypto.base64Encode(iv).map { ivBase64 ->
                        Nip04CipherText(cipherTextBase64 = cipherBase64, ivBase64 = ivBase64)
                    }
                }
            }
        }
    }
}

fun NipModuleContext.encryptNip04Content(
    plaintext: String,
    senderPrivateKey: ByteString,
    recipientPublicKey: PublicKey,
): CryptoResult<String> =
    encryptNip04(plaintext, senderPrivateKey, recipientPublicKey).map { it.toContent() }

fun NipModuleContext.decryptNip04(
    ciphertext: Nip04CipherText,
    receiverPrivateKey: ByteString,
    senderPublicKey: PublicKey,
): CryptoResult<String> =
    crypto.ecdhXOnly(receiverPrivateKey, senderPublicKey.bytes).flatMap { sharedSecret ->
        crypto.base64Decode(ciphertext.ivBase64).flatMap { iv ->
            if (iv.size != 16) {
                CryptoResult.Err(CryptoError.InvalidInput("iv", "16 bytes", iv.size))
            } else {
                crypto.base64Decode(ciphertext.cipherTextBase64).flatMap { cipherBytes ->
                    crypto.aes256CbcDecrypt(sharedSecret, iv, cipherBytes).map { plainBytes ->
                        plainBytes.toByteArray().decodeToString()
                    }
                }
            }
        }
    }

fun NipModuleContext.decryptNip04Content(
    content: String,
    receiverPrivateKey: ByteString,
    senderPublicKey: PublicKey,
): CryptoResult<String> {
    val ciphertext = Nip04CipherText.parseOrNull(content)
        ?: return CryptoResult.Err(CryptoError.InvalidInput("content", "nip04 format", content.length))
    return decryptNip04(ciphertext, receiverPrivateKey, senderPublicKey)
}
