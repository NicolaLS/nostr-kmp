package io.github.nicolals.nostr.nip04

import io.github.nicolals.nostr.core.crypto.CryptoError
import io.github.nicolals.nostr.core.crypto.CryptoPrimitives
import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.nip04.model.Nip04CipherText
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.text.CharacterCodingException

fun CryptoPrimitives.encryptNip04(
    plaintext: String,
    senderPrivateKey: ByteString,
    recipientPublicKey: PublicKey,
): CryptoResult<Nip04CipherText> {
    val plaintextBytes = try {
        plaintext.encodeToByteArray(throwOnInvalidSequence = true).toByteString()
    } catch (_: CharacterCodingException) {
        return CryptoResult.Err(CryptoError.InvalidInput("plaintext", "valid Unicode", plaintext.length))
    }
    return ecdhXOnly(senderPrivateKey, recipientPublicKey.bytes).flatMap { sharedSecret ->
        randomBytes(16).flatMap { iv ->
            aes256CbcEncrypt(sharedSecret, iv, plaintextBytes).flatMap { cipherBytes ->
                base64Encode(cipherBytes).flatMap { cipherBase64 ->
                    base64Encode(iv).map { ivBase64 ->
                        Nip04CipherText(cipherTextBase64 = cipherBase64, ivBase64 = ivBase64)
                    }
                }
            }
        }
    }
}

fun CryptoPrimitives.encryptNip04Content(
    plaintext: String,
    senderPrivateKey: ByteString,
    recipientPublicKey: PublicKey,
): CryptoResult<String> =
    encryptNip04(plaintext, senderPrivateKey, recipientPublicKey).map { it.toContent() }

fun CryptoPrimitives.decryptNip04(
    ciphertext: Nip04CipherText,
    receiverPrivateKey: ByteString,
    senderPublicKey: PublicKey,
): CryptoResult<String> =
    ecdhXOnly(receiverPrivateKey, senderPublicKey.bytes).flatMap { sharedSecret ->
        base64Decode(ciphertext.ivBase64).flatMap { iv ->
            if (iv.size != 16) {
                CryptoResult.Err(CryptoError.InvalidInput("iv", "16 bytes", iv.size))
            } else {
                base64Decode(ciphertext.cipherTextBase64).flatMap { cipherBytes ->
                    aes256CbcDecrypt(sharedSecret, iv, cipherBytes).flatMap { plainBytes ->
                        try {
                            CryptoResult.Ok(plainBytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
                        } catch (_: CharacterCodingException) {
                            CryptoResult.Err(CryptoError.DecryptionFailed("invalid UTF-8"))
                        }
                    }
                }
            }
        }
    }

fun CryptoPrimitives.decryptNip04Content(
    content: String,
    receiverPrivateKey: ByteString,
    senderPublicKey: PublicKey,
): CryptoResult<String> {
    val ciphertext = Nip04CipherText.parseOrNull(content)
        ?: return CryptoResult.Err(CryptoError.InvalidInput("content", "nip04 format", content.length))
    return decryptNip04(ciphertext, receiverPrivateKey, senderPublicKey)
}
