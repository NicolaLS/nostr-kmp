package io.github.nicolals.nostr.nip47.model

import io.github.nicolals.nostr.core.codec.JsonValue

sealed interface NwcNotification {
    val notificationType: String

    companion object {
        const val TYPE_PAYMENT_RECEIVED = "payment_received"
        const val TYPE_PAYMENT_SENT = "payment_sent"
    }
}

data class NwcPaymentReceivedNotification(
    val transaction: NwcTransaction,
) : NwcNotification {
    override val notificationType: String = NwcNotification.TYPE_PAYMENT_RECEIVED
}

data class NwcPaymentSentNotification(
    val transaction: NwcTransaction,
) : NwcNotification {
    override val notificationType: String = NwcNotification.TYPE_PAYMENT_SENT
}

data class NwcUnknownNotification(
    override val notificationType: String,
    val value: JsonValue? = null,
) : NwcNotification
