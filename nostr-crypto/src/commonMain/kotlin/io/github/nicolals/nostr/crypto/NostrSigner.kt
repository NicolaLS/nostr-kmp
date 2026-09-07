package io.github.nicolals.nostr.crypto

import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.crypto.EventTemplateSigner
import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.assemble.EventAssembler
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.primitives.KeyPair
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.core.primitives.SecretKey

/** Local Schnorr signer. Creation derives and validates the public key from the private key. */
class NostrSigner private constructor(
    private val secretKey: SecretKey,
    override val pubkey: PublicKey,
) : EventTemplateSigner {
    override suspend fun sign(draft: EventDraft): CryptoResult<Event> =
        EventAssembler.sign(draft, pubkey) { id -> NostrSigning.schnorrSign(secretKey.bytes, id) }

    override fun toString(): String = "NostrSigner(pubkey=$pubkey)"

    companion object {
        fun generate(): CryptoResult<NostrSigner> =
            NostrSigning.generateSecretKey().flatMap { fromSecretKey(SecretKey.fromBytes(it)) }

        fun fromSecretKey(secretKey: SecretKey): CryptoResult<NostrSigner> =
            NostrSigning.derivePublicKeyXOnly(secretKey.bytes).map { publicKey ->
                NostrSigner(secretKey, PublicKey.fromBytes(publicKey))
            }
    }
}

fun SecretKey.toKeyPair(): CryptoResult<KeyPair> =
    NostrSigning.derivePublicKeyXOnly(bytes).map { publicKey -> KeyPair(this, PublicKey.fromBytes(publicKey)) }

fun KeyPair.Companion.generate(): CryptoResult<KeyPair> =
    NostrSigning.generateSecretKey().flatMap { SecretKey.fromBytes(it).toKeyPair() }
