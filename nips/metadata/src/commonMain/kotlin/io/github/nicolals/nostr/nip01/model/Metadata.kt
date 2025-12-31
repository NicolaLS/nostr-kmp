package io.github.nicolals.nostr.nip01.model

open class Metadata(
    val name: String,
    val about: String,
    val picture: String,
    val displayName: String? = null,
    val website: String? = null,
    val banner: String? = null,
    val bot: Boolean? = null,
    val birthday: Birthday? = null,
)
