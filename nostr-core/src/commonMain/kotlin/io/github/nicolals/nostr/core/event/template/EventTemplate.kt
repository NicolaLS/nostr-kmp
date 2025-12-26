package io.github.nicolals.nostr.core.event.template

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.event.EventKind
import io.github.nicolals.nostr.core.event.EventTags
import io.github.nicolals.nostr.core.event.view.EventView
import io.github.nicolals.nostr.core.event.view.GenericEvent


/**
 * Represents an unsigned event draft plus a typed wrapper factory for the final signed [Event].
 *
 * ## Important
 * - `wrap(event)` MUST be cheap. It should generally be `::MyTypedView`.
 * - If the typed view would otherwise need expensive decrypt/parse to expose properties,
 *   the wrap closure may capture "known fields" (derived at template creation/upgrade time)
 *   in the `EventTemplateUpgrader` implementation and pass them into the typed view to avoid repeating
 *   work after signing.
 *
 *   Example:
 *   ```kotlin
 *   object NwcPayReqTemplate : EventTemplateUpgrader<NwcPayReq> {
 *     override val kind: EventKind = EventKind(23194)
 *
 *     fun create(amount: Long): EventTemplate<NwcPayReq> {
 *         val known = NwcPayKnown(amount)
 *         val payload = somehowBuildPayload(amount)
 *         val content = encrypt(payload)
 *         val tags = somehowBuildTags()
 *         // ...
 *
 *         val draft = EventDraft(createdAt, kind, tags, content)
 *         return EventTemplate(draft) { event -> NwcPayReq(event, known = known, decryptor = null) }
 *     }
 *
 *     override fun upgradeOrNull(draft: EventDraft): EventTemplate<NwcPayReq> {
 *       // ...
 *     }
 * }
 *
 *   ```
 */
class EventTemplate<U : EventView>(
    val draft: EventDraft,
    internal val wrap: (Event) -> U,
)

fun eventTemplate(
    kind: EventKind,
    createdAt: Long,
    tags: EventTags,
    content: String,
): EventTemplate<GenericEvent> = EventTemplate(
    draft = EventDraft(createdAt, kind, tags, content),
    wrap = ::GenericEvent
)

fun <U : EventView> EventTemplate<*>.upgradeToOrNull(
    upgrader: EventTemplateUpgrader<U>
): EventTemplate<U>? = upgrader.upgradeOrNull(this.draft)


// TODO: Provide update and builder ergonomics.