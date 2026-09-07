package io.github.nicolals.nostr.nip50

import io.github.nicolals.nostr.core.codec.JsonString
import io.github.nicolals.nostr.core.message.Filter

/** The NIP-50 search query, when this filter contains one. */
val Filter.search: String?
    get() = (extraFields["search"] as? JsonString)?.value

/** Adds or replaces a NIP-50 query. Pass null to remove it. No module registration is needed. */
fun Filter.withSearch(query: String?): Filter = copy(
    extraFields = if (query == null) extraFields - "search" else extraFields + ("search" to JsonString(query)),
)
