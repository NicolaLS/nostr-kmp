package nostr.core.codec

import nostr.core.model.ClientMessage
import nostr.core.model.Event
import nostr.core.model.Filter
import nostr.core.model.RelayMessage

/**
 * Parses JSON frames received from relays or produced by clients.
 */
interface WireDecoder {
    @Throws(WireDecodingException::class)
    fun relayMessage(raw: String): RelayMessage

    @Throws(WireDecodingException::class)
    fun clientMessage(raw: String): ClientMessage

    @Throws(WireDecodingException::class)
    fun event(raw: String): Event

    @Throws(WireDecodingException::class)
    fun filter(raw: String): Filter
}

class WireDecodingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
