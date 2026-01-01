package io.github.nicolals.nostr.nip47.event

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventTag
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.nip47.model.NwcEncryption

open class NwcInfoEvent(
    override val event: Event,
    val capabilities: List<String>,
    val notificationTypes: List<String>,
    val encryptionCodes: List<String>,
    val supportedEncryptions: List<NwcEncryption>,
) : EventView {
    companion object {
        val KIND = EventKind(13194)

        fun fromEventOrNull(event: Event): NwcInfoEvent? {
            if (event.kind != KIND) return null
            val capabilities = parseSpaceSeparated(event.content)
            val encryptionTag = tagValue(event.tags, "encryption")
            val encryptionCodes = parseSpaceSeparated(encryptionTag)
            val supportedEncryptions = if (encryptionCodes.isEmpty()) {
                listOf(NwcEncryption.NIP04)
            } else {
                encryptionCodes.mapNotNull(NwcEncryption::fromTag)
            }
            val notificationTypes = parseSpaceSeparated(tagValue(event.tags, "notifications"))
            return NwcInfoEvent(
                event = event,
                capabilities = capabilities,
                notificationTypes = notificationTypes,
                encryptionCodes = encryptionCodes,
                supportedEncryptions = supportedEncryptions,
            )
        }

        fun template(
            capabilities: List<String>,
            createdAt: UnixTimeSeconds,
            encryption: List<NwcEncryption> = emptyList(),
            notifications: List<String> = emptyList(),
            extraTags: EventTags = emptyList(),
        ): EventTemplate<NwcInfoEvent> {
            val encryptionCodes = encryption.map { it.tag }
            val tags = buildTags(encryptionCodes, notifications, extraTags)
            val content = capabilities.joinToString(" ")
            val draft = EventDraft(
                createdAt = createdAt,
                kind = KIND,
                tags = tags,
                content = content,
            )
            val supportedEncryptions = if (encryption.isEmpty()) {
                listOf(NwcEncryption.NIP04)
            } else {
                encryption
            }
            return EventTemplate(draft) { event ->
                NwcInfoEvent(
                    event = event,
                    capabilities = capabilities,
                    notificationTypes = notifications,
                    encryptionCodes = encryptionCodes,
                    supportedEncryptions = supportedEncryptions,
                )
            }
        }
    }
}

private fun buildTags(
    encryptionCodes: List<String>,
    notifications: List<String>,
    extraTags: EventTags,
): EventTags {
    val tags = mutableListOf<EventTag>()
    if (encryptionCodes.isNotEmpty()) {
        tags += listOf("encryption", encryptionCodes.joinToString(" "))
    }
    if (notifications.isNotEmpty()) {
        tags += listOf("notifications", notifications.joinToString(" "))
    }
    tags.addAll(extraTags)
    return tags
}

private fun tagValue(tags: EventTags, name: String): String? =
    tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

private fun parseSpaceSeparated(value: String?): List<String> =
    value?.trim()?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
