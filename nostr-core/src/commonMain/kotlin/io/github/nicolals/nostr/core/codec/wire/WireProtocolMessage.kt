package io.github.nicolals.nostr.core.codec.wire

sealed interface WireProtocolMessage

sealed interface WireClientMessage : WireProtocolMessage

data class WireClientEventMessage(
    val event: WireEvent,
) : WireClientMessage

data class WireClientReqMessage(
    val subscriptionId: String,
    val filters: List<WireFilter>,
) : WireClientMessage

data class WireClientCloseMessage(
    val subscriptionId: String,
) : WireClientMessage

sealed interface WireRelayMessage : WireProtocolMessage

data class WireRelayEventMessage(
    val subscriptionId: String,
    val event: WireEvent,
) : WireRelayMessage

data class WireRelayOkMessage(
    val eventId: String,
    val accepted: Boolean,
    val message: String,
) : WireRelayMessage

data class WireRelayEoseMessage(
    val subscriptionId: String,
) : WireRelayMessage

data class WireRelayClosedMessage(
    val subscriptionId: String,
    val message: String,
) : WireRelayMessage

data class WireRelayNoticeMessage(
    val message: String,
) : WireRelayMessage
