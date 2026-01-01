package io.github.nicolals.nostr.nip01

import io.github.nicolals.nostr.core.codec.*
import io.github.nicolals.nostr.core.event.upgrade.EventUpgrader
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.nip.NipModule
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.nip01.event.MetadataEventUpgrader
import io.github.nicolals.nostr.nip01.model.Birthday
import io.github.nicolals.nostr.nip01.model.Metadata

/**
 * This module provides the `[io.github.nicolals.nostr.nip01.event.MetadataEvent] event view with
 * ergonomics for nip-01 event kind 0 events and nip-24 extra fields on those events.
 */
class MetadataModule(
    private val ctx: NipModuleContext,
) : NipModule {
    override val eventUpgraders: List<EventUpgrader<out EventView>> =
        listOf(MetadataEventUpgrader(ctx))
}

fun NipModuleContext.encodeContent(metadata: Metadata): String {
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
    return jsonCodec.stringify(value)
}

fun NipModuleContext.decodeContentOrNull(content: String): Metadata? {
    val value = jsonCodec.parseObjectOrNull(content) ?: return null
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
