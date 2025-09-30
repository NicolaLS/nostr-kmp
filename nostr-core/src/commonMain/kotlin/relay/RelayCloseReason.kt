package nostr.core.relay

/** Common websocket close codes referenced by the runtime. */
enum class RelayCloseReason(val code: Int) {
    Normal(1000),
    GoingAway(1001),
    ProtocolError(1002),
    UnsupportedData(1003),
    PolicyViolation(1008),
    TooLarge(1009);

    companion object {
        fun of(code: Int): RelayCloseReason? = entries.firstOrNull { it.code == code }
    }
}
