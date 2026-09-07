package io.github.nicolals.nostr.nip01

import io.github.nicolals.nostr.core.codec.*
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.message.upgrade.ClientMessageUpgrader
import io.github.nicolals.nostr.core.message.upgrade.RelayMessageUpgrader
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.codec.JsonCodec
import io.github.nicolals.nostr.core.protocol.ProtocolReducer
import io.github.nicolals.nostr.nip01.event.MetadataEventUpgrader
import io.github.nicolals.nostr.nip01.message.Nip01ClientMessageUpgrader
import io.github.nicolals.nostr.nip01.message.Nip01RelayMessageUpgrader
import io.github.nicolals.nostr.nip01.protocol.Nip01Reducer
import io.github.nicolals.nostr.nip01.model.Birthday
import io.github.nicolals.nostr.nip01.model.Metadata

/**
 * NIP-01 module providing:
 * - Event views for kind 0 metadata events (with NIP-24 extra fields)
 * - Protocol message views for client messages (EVENT, REQ, CLOSE)
 * - Protocol message views for relay messages (EVENT, OK, EOSE, CLOSED, NOTICE)
 */
class Nip01Module(
    codec: NostrJsonCodec,
) : NipModule {
    override val id: String = "nip01"

    override val eventUpgraders: List<EventUpgrader<out EventView>> =
        listOf(MetadataEventUpgrader(codec))

    override val clientMessageUpgraders: List<ClientMessageUpgrader<*>> =
        listOf(Nip01ClientMessageUpgrader(codec))

    override val relayMessageUpgraders: List<RelayMessageUpgrader<*>> =
        listOf(Nip01RelayMessageUpgrader(codec))

    override val protocolReducers: List<ProtocolReducer> =
        listOf(Nip01Reducer(codec))
}

fun JsonCodec.encodeContent(metadata: Metadata): String {
    val birthday = metadata.birthday?.toJsonObjectOrNull()
    val value = jsonObjectOf(
        "name" to JsonString(metadata.name),
        "about" to JsonString(metadata.about),
        "picture" to JsonString(metadata.picture),
        "display_name" to metadata.displayName?.let(::JsonString),
        "website" to metadata.website?.let(::JsonString),
        "banner" to metadata.banner?.let(::JsonString),
        "bot" to metadata.bot?.let(::JsonBoolean),
        "birthday" to birthday,
    )
    return stringify(value)
}

fun JsonCodec.decodeContentOrNull(content: String): Metadata? {
    val value = parseObjectOrNull(content) ?: return null
    val name = value.stringValue("name") ?: return null
    val about = value.stringValue("about") ?: return null
    val picture = value.stringValue("picture") ?: return null
    val displayName = value.stringValue("display_name")
    val website = value.stringValue("website")
    val banner = value.stringValue("banner")
    val bot = value.booleanValue("bot")
    val birthday = value.objectValue("birthday")?.let { birthdayObj ->
        val year = birthdayObj.intValueOrNull("year")
        val month = birthdayObj.intValueOrNull("month")
        val day = birthdayObj.intValueOrNull("day")
        if (year == null && month == null && day == null) {
            null
        } else {
            Birthday(year = year, month = month, day = day)
        }
    }
    return Metadata(
        name = name,
        about = about,
        picture = picture,
        displayName = displayName,
        website = website,
        banner = banner,
        bot = bot,
        birthday = birthday,
    )
}

private fun Birthday.toJsonObjectOrNull(): JsonObject? {
    val obj = jsonObjectOf(
        "year" to year?.let { JsonNumber(it.toString()) },
        "month" to month?.let { JsonNumber(it.toString()) },
        "day" to day?.let { JsonNumber(it.toString()) },
    )
    return obj.takeIf { it.entries.isNotEmpty() }
}
