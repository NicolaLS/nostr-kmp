package io.github.nicolals.nostr.nip47.model

enum class NwcEncryption(val tag: String) {
    NIP44_V2("nip44_v2"),
    NIP04("nip04");

    companion object {
        fun fromTag(tag: String?): NwcEncryption? = when (tag?.lowercase()) {
            NIP44_V2.tag -> NIP44_V2
            NIP04.tag -> NIP04
            else -> null
        }
    }
}
