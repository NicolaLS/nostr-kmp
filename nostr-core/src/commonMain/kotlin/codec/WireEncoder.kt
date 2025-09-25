package nostr.core.codec

import nostr.core.model.ClientMessage
import nostr.core.model.Event
import nostr.core.model.Filter

/**
 * Converts domain objects into their wire (JSON) representation.
 */
interface WireEncoder {
    fun clientMessage(message: ClientMessage): String
    fun event(event: Event): String
    fun filter(filter: Filter): String
}
