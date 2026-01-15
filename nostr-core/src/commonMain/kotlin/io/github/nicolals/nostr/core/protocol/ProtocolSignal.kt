package io.github.nicolals.nostr.core.protocol

import io.github.nicolals.nostr.core.transport.CloseInfo
import io.github.nicolals.nostr.core.transport.TransportError

/**
 * Signals emitted by the protocol engine for consumers to observe.
 *
 * Signals are notifications about state changes and protocol events.
 * Unlike [ProtocolEffect], signals don't require runtime action - they're
 * for observation only.
 *
 * This is an open interface: NIP modules define their own signal types
 * (e.g., subscription events, publish status changes). The runtime surfaces
 * signals to consumers via flows or callbacks.
 */
interface ProtocolSignal

/**
 * Core signals related to connection lifecycle.
 *
 * These signals are emitted by the core protocol engine (not NIP-specific).
 */
sealed interface CoreSignal : ProtocolSignal {

    /**
     * The connection to the relay has been established.
     */
    data object ConnectionOpened : CoreSignal

    /**
     * The connection to the relay was closed normally.
     *
     * @property info Details about the close.
     */
    data class ConnectionClosed(val info: CloseInfo) : CoreSignal

    /**
     * The connection to the relay failed.
     *
     * @property error The transport error that occurred.
     */
    data class ConnectionFailed(val error: TransportError) : CoreSignal
}
