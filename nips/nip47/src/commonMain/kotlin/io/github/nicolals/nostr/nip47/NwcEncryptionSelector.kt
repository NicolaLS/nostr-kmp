package io.github.nicolals.nostr.nip47

import io.github.nicolals.nostr.core.primitives.PublicKey
import io.github.nicolals.nostr.nip47.event.NwcInfoEvent
import io.github.nicolals.nostr.nip47.model.NwcEncryption

fun interface NwcEncryptionSelector {
    fun select(walletPubkey: PublicKey): NwcEncryption
}

fun nwcEncryptionSelectorByWalletPubkey(
    mapping: Map<PublicKey, NwcEncryption>,
    fallback: NwcEncryption,
): NwcEncryptionSelector = NwcEncryptionSelector { walletPubkey ->
    mapping[walletPubkey] ?: fallback
}

fun NwcInfoEvent.preferredEncryption(
    preferred: NwcEncryption = NwcEncryption.NIP44_V2,
): NwcEncryption {
    val supported = if (supportedEncryptions.isEmpty()) {
        listOf(NwcEncryption.NIP04)
    } else {
        supportedEncryptions
    }
    return if (preferred in supported) preferred else supported.first()
}

fun nwcEncryptionSelectorByInfoEvent(
    infoEvent: NwcInfoEvent,
    fallback: NwcEncryption,
    preferred: NwcEncryption = NwcEncryption.NIP44_V2,
): NwcEncryptionSelector = NwcEncryptionSelector { walletPubkey ->
    if (walletPubkey == infoEvent.event.pubkey) {
        infoEvent.preferredEncryption(preferred)
    } else {
        fallback
    }
}

fun nwcEncryptionSelectorByInfoEvents(
    infoEvents: Map<PublicKey, NwcInfoEvent>,
    fallback: NwcEncryption,
    preferred: NwcEncryption = NwcEncryption.NIP44_V2,
): NwcEncryptionSelector = NwcEncryptionSelector { walletPubkey ->
    infoEvents[walletPubkey]?.preferredEncryption(preferred) ?: fallback
}
