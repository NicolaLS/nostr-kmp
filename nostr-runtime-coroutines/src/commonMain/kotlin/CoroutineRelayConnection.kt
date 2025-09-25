package nostr.runtime.coroutines

import kotlinx.coroutines.flow.Flow

interface CoroutineRelayConnection {
    val url: String
    val incoming: Flow<String>
    suspend fun open()
    suspend fun send(frame: String)
    suspend fun close(code: Int = 1000, reason: String? = null)
}

fun interface CoroutineRelayConnectionFactory {
    suspend fun create(url: String): CoroutineRelayConnection
}
