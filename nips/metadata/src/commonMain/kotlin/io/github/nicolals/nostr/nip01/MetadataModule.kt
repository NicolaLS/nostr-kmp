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
    val entries = mutableMapOf<String, JsonValue>(
        "name" to JsonString(metadata.name),
        "about" to JsonString(metadata.about),
        "picture" to JsonString(metadata.picture),
    )
    metadata.displayName?.let { entries["display_name"] = JsonString(it) }
    metadata.website?.let { entries["website"] = JsonString(it) }
    metadata.banner?.let { entries["banner"] = JsonString(it) }
    metadata.bot?.let { entries["bot"] = JsonBoolean(it) }
    metadata.birthday?.let { birthday ->
        val birthdayEntries = mutableMapOf<String, JsonValue>()
        birthday.year?.let { birthdayEntries["year"] = JsonNumber(it.toString()) }
        birthday.month?.let { birthdayEntries["month"] = JsonNumber(it.toString()) }
        birthday.day?.let { birthdayEntries["day"] = JsonNumber(it.toString()) }
        if (birthdayEntries.isNotEmpty()) {
            entries["birthday"] = JsonObject(birthdayEntries)
        }
    }
    return jsonCodec.stringify(JsonObject(entries))
}

fun NipModuleContext.decodeContentOrNull(content: String): Metadata? {
    val value = runCatching { jsonCodec.parse(content) }.getOrNull() ?: return null
    when (value) {
        is JsonObject -> {
            val name = value.stringValue("name") ?: return null
            val about = value.stringValue("about") ?: return null
            val picture = value.stringValue("picture") ?: return null
            val displayName = value.stringValue("display_name")
            val website = value.stringValue("website")
            val banner = value.stringValue("banner")
            val bot = value.booleanValue("bot")
            val birthday = (value.entries["birthday"] as? JsonObject)?.let { birthdayObj ->
                val year = birthdayObj.intValue("year")
                val month = birthdayObj.intValue("month")
                val day = birthdayObj.intValue("day")
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

        else -> return null
    }
}

private fun JsonObject.stringValue(key: String): String? =
    (entries[key] as? JsonString)?.value

private fun JsonObject.booleanValue(key: String): Boolean? =
    (entries[key] as? JsonBoolean)?.value

private fun JsonObject.intValue(key: String): Int? =
    when (val value = entries[key]) {
        is JsonNumber -> value.value.toIntOrNull()
        is JsonString -> value.value.toIntOrNull()
        else -> null
    }
