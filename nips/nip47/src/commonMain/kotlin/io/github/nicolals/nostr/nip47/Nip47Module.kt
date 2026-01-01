package io.github.nicolals.nostr.nip47

import io.github.nicolals.nostr.core.crypto.CryptoResult
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.nip04.decryptNip04Content
import io.github.nicolals.nostr.nip04.encryptNip04Content
import io.github.nicolals.nostr.nip44.decryptNip44Content
import io.github.nicolals.nostr.nip44.encryptNip44Content
import io.github.nicolals.nostr.nip47.event.NwcInfoEventUpgrader
import io.github.nicolals.nostr.nip47.event.NwcNotificationEventUpgrader
import io.github.nicolals.nostr.nip47.event.NwcRequestEventUpgrader
import io.github.nicolals.nostr.nip47.event.NwcResponseEventUpgrader
import io.github.nicolals.nostr.nip47.model.NwcEncryption
import okio.ByteString

/**
 * NIP-47 Nostr Wallet Connect module for event views and payload helpers.
 */
class Nip47Module(
    ctx: NipModuleContext,
    private val encryptionSelector: NwcEncryptionSelector,
) : NipModule {
    override val eventUpgraders: List<EventUpgrader<out EventView>> =
        listOf(
            NwcInfoEventUpgrader(),
            NwcRequestEventUpgrader(encryptionSelector),
            NwcResponseEventUpgrader(encryptionSelector),
            NwcNotificationEventUpgrader(),
        )
}

fun NipModuleContext.encryptNwcContent(
    plaintext: String,
    encryption: NwcEncryption,
    senderPrivateKey: ByteString,
    recipientPublicKey: PublicKey,
): CryptoResult<String> = when (encryption) {
    NwcEncryption.NIP44_V2 -> encryptNip44Content(plaintext, senderPrivateKey, recipientPublicKey)
    NwcEncryption.NIP04 -> encryptNip04Content(plaintext, senderPrivateKey, recipientPublicKey)
}

fun NipModuleContext.decryptNwcContent(
    content: String,
    encryption: NwcEncryption,
    receiverPrivateKey: ByteString,
    senderPublicKey: PublicKey,
): CryptoResult<String> = when (encryption) {
    NwcEncryption.NIP44_V2 -> decryptNip44Content(content, receiverPrivateKey, senderPublicKey)
    NwcEncryption.NIP04 -> decryptNip04Content(content, receiverPrivateKey, senderPublicKey)
}
