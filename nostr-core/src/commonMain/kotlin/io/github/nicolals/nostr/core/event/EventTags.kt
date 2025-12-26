package io.github.nicolals.nostr.core.event

typealias EventTag = List<String>
/**
 * A tag array of a signed nostr event.
 *
 * This is modeled as a `List<List<String>> (a **read-only** view) to prevent accidental mutation,
 * as mutating tags of an already signed event breaks the `Event.id` and `Event.sig` which will
 * lead to rejection by relays and errors in other modules of this SDK.
 *
 * In case you need to mutate tags for some reason you can cast it to a `MutableList` but be aware
 * that this breaks the event in which these tags are included.
 */
typealias EventTags = List<EventTag>