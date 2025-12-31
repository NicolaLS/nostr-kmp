package io.github.nicolals.nostr.nip01.event

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.template.EventDraft
import io.github.nicolals.nostr.core.event.template.EventTemplate
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.nip.NipModuleContext
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.EventTags
import io.github.nicolals.nostr.core.primitives.UnixTimeSeconds
import io.github.nicolals.nostr.nip01.decodeContentOrNull
import io.github.nicolals.nostr.nip01.encodeContent
import io.github.nicolals.nostr.nip01.model.Birthday
import io.github.nicolals.nostr.nip01.model.Metadata

open class MetadataEvent(
    override val event: Event,
    open val metadata: Metadata
) : EventView {
    companion object {
        val KIND = EventKind(0)

        fun fromEventOrNull(event: Event, ctx: NipModuleContext): MetadataEvent? {
            if (event.kind != KIND) {
                return null
            }
            val metadata = ctx.decodeContentOrNull(event.content) ?: return null
            return MetadataEvent(event = event, metadata = metadata)
        }

        fun template(
            name: String,
            about: String,
            picture: String,
            createdAt: UnixTimeSeconds,
            tags: EventTags,
            ctx: NipModuleContext,
            displayName: String? = null,
            website: String? = null,
            banner: String? = null,
            bot: Boolean? = null,
            birthday: Birthday? = null,
        ): EventTemplate<MetadataEvent> {
            val metadata = Metadata(
                name = name,
                about = about,
                picture = picture,
                displayName = displayName,
                website = website,
                banner = banner,
                bot = bot,
                birthday = birthday,
            )
            return template(
                metadata = metadata,
                createdAt = createdAt,
                tags = tags,
                ctx = ctx
            )
        }

        fun template(
            metadata: Metadata,
            createdAt: UnixTimeSeconds,
            tags: EventTags,
            ctx: NipModuleContext
        ): EventTemplate<MetadataEvent> {
            val metadataContent = ctx.encodeContent(metadata)
            val draft = EventDraft(
                createdAt = createdAt,
                kind = KIND,
                tags = tags,
                content = metadataContent,
            )
            return EventTemplate(draft) { event -> MetadataEvent(event = event, metadata = metadata) }
        }
    }

    val name get() = metadata.name
    val about get() = metadata.about
    val picture get() = metadata.picture
    val displayName get() = metadata.displayName
    val website get() = metadata.website
    val banner get() = metadata.banner
    val bot get() = metadata.bot
    val birthday get() = metadata.birthday
}
