package nostr.core.relay

/** Factory for producing [RelayConnection] instances for a given relay URL. */
fun interface RelayConnectionFactory {
    fun create(url: String): RelayConnection
}
