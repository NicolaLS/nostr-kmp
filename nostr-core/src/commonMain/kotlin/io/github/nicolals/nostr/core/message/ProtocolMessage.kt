package io.github.nicolals.nostr.core.message

import io.github.nicolals.nostr.core.event.Event
import io.github.nicolals.nostr.core.types.HexKey

typealias SubscriptionId = String

sealed interface ProtocolMessage

sealed interface ClientMessage : ProtocolMessage

data class ClientEventMessage(
    val event: Event,
) : ClientMessage

data class ClientReqMessage(
    val subscriptionId: SubscriptionId,
    val filters: List<Filter>,
) : ClientMessage

data class ClientCloseMessage(
    val subscriptionId: SubscriptionId,
) : ClientMessage

sealed interface RelayMessage : ProtocolMessage

data class RelayEventMessage(
    val subscriptionId: SubscriptionId,
    val event: Event,
) : RelayMessage

data class RelayOkMessage(
    val eventId: HexKey,
    val accepted: Boolean,
    val message: String,
) : RelayMessage

data class RelayEoseMessage(
    val subscriptionId: SubscriptionId,
) : RelayMessage

data class RelayClosedMessage(
    val subscriptionId: SubscriptionId,
    val message: String,
) : RelayMessage

data class RelayNoticeMessage(
    val message: String,
) : RelayMessage
