# nostr-runtime-coroutines Module Documentation

`nostr-runtime-coroutines` provides a coroutine-based runtime for managing Nostr relay connections. It wraps the `RelaySessionEngine` from `nostr-core` and handles WebSocket lifecycle, message encoding/decoding, timeouts, and automatic reconnection.

## Quick Start

```kotlin
val runtime = CoroutineNostrRuntime(
    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    connectionFactory = KtorRelayConnectionFactory(scope, httpClient),
    wireEncoder = codec,
    wireDecoder = codec
)

runtime.connect("wss://relay.example.com")
runtime.subscribe("my-sub", listOf(Filter(kinds = setOf(1))))

// Observe events
runtime.outputs.collect { output ->
    when (output) {
        is RelaySessionOutput.Event -> println("Got event: ${output.event}")
        is RelaySessionOutput.EndOfStoredEvents -> println("EOSE for ${output.subscriptionId}")
        else -> {}
    }
}
```

## Configuration Reference

### CoroutineNostrRuntime

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `scope` | `CoroutineScope` | required | Parent scope for runtime coroutines |
| `connectionFactory` | `RelayConnectionFactory` | required | Factory to create relay connections |
| `wireEncoder` | `WireEncoder` | required | Encodes client messages to JSON |
| `wireDecoder` | `WireDecoder` | required | Decodes relay messages from JSON |
| `connectTimeoutMillis` | `Long` | **2,000ms** | Maximum time to complete WebSocket handshake |
| `readTimeoutMillis` | `Long` | **15,000ms** | Idle timeout - connection considered dead if no frames received |
| `reconnectionPolicy` | `ReconnectionPolicy` | `NoReconnectionPolicy` | Strategy for automatic reconnection after failures |
| `settings` | `RelaySessionSettings` | defaults | Session engine settings (see nostr-core docs) |
| `reducer` | `RelaySessionReducer` | `DefaultRelaySessionReducer()` | State reducer implementation |
| `initialState` | `RelaySessionState` | empty | Initial session state |
| `interceptors` | `List<CoroutineRuntimeInterceptor>` | empty | Lifecycle interceptors for logging/monitoring |

### RelaySessionManager

Manages multiple relay sessions with shared configuration:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `scope` | `CoroutineScope` | required | Parent scope for all sessions |
| `connectionFactory` | `RelayConnectionFactory` | required | Factory to create relay connections |
| `wireEncoder` | `WireEncoder` | required | Encodes client messages to JSON |
| `wireDecoder` | `WireDecoder` | required | Decodes relay messages from JSON |
| `sessionSettings` | `RelaySessionSettings` | defaults | Settings passed to each session |
| `sessionReducer` | `RelaySessionReducer` | `DefaultRelaySessionReducer()` | Reducer for each session |
| `initialState` | `RelaySessionState` | empty | Initial state for new sessions |
| `connectTimeoutMillis` | `Long` | **2,000ms** | Handshake timeout for all sessions |
| `readTimeoutMillis` | `Long` | **15,000ms** | Idle timeout for all sessions |
| `reconnectionPolicy` | `ReconnectionPolicy` | `NoReconnectionPolicy` | Reconnection strategy for all sessions |

## Reconnection Policies

The runtime supports pluggable reconnection strategies via the `ReconnectionPolicy` interface.

### NoReconnectionPolicy (default)

Disables automatic reconnection. Use this when you want full manual control:

```kotlin
val runtime = CoroutineNostrRuntime(
    // ... required params ...
    reconnectionPolicy = NoReconnectionPolicy
)
```

After a connection failure, the runtime stays disconnected until you explicitly call `connect()` again.

### ExponentialBackoffPolicy

Reconnects with exponentially increasing delays. Ideal for most production use cases:

```kotlin
val runtime = CoroutineNostrRuntime(
    // ... required params ...
    reconnectionPolicy = ExponentialBackoffPolicy(
        baseDelayMillis = 500L,      // First retry after 500ms (default)
        maxDelayMillis = 15_000L,    // Cap at 15 seconds (default)
        maxAttempts = 10,            // Give up after 10 attempts (default), null = unlimited
        jitterFactor = 0.25          // +/- 25% randomness (default)
    )
)
```

**Delay progression (with defaults):** 500ms → 1s → 2s → 4s → 8s → 15s → 15s → ...

The jitter factor adds randomness to prevent thundering herd when many clients reconnect simultaneously.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `baseDelayMillis` | **500ms** | Initial delay before first retry |
| `maxDelayMillis` | **15,000ms** | Maximum delay cap |
| `maxAttempts` | **10** | Stop retrying after N attempts (`null` = unlimited) |
| `jitterFactor` | **0.25** | Random variation (0.0 to 1.0) |

### FixedDelayPolicy

Reconnects with a constant delay between attempts:

```kotlin
val runtime = CoroutineNostrRuntime(
    // ... required params ...
    reconnectionPolicy = FixedDelayPolicy(
        delayMillis = 1_000L,  // Wait 1 second between attempts (default)
        maxAttempts = 5        // Give up after 5 attempts (default), null = unlimited
    )
)
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `delayMillis` | **1,000ms** | Constant delay between retries |
| `maxAttempts` | **5** | Stop retrying after N attempts (`null` = unlimited) |

### Custom Policies

Implement `ReconnectionPolicy` for custom behavior:

```kotlin
class AggressiveRetryPolicy : ReconnectionPolicy {
    override fun nextDelay(attempt: Int, failure: ConnectionFailure?): Long? {
        // Immediate retry for first 3 attempts, then give up
        return if (attempt <= 3) 100L else null
    }
}
```

The `ConnectionFailure` parameter provides context about why the connection failed:

```kotlin
data class ConnectionFailure(
    val url: String?,
    val reason: ConnectionFailureReason,
    val message: String?,
    val attempt: Int,
    val closeCode: Int?,
    val closeReason: String?,
    val cause: String?
)

enum class ConnectionFailureReason {
    ConnectionFactory,  // Failed to create connection
    OpenHandshake,      // WebSocket handshake failed or timed out
    StreamFailure       // Connection dropped while active
}
```

## Timeout Behavior

### Connect Timeout (`connectTimeoutMillis`)

Maximum time allowed for the WebSocket handshake to complete. If exceeded:
- Connection attempt is cancelled
- `ConnectionFailureReason.OpenHandshake` is reported
- Reconnection policy is consulted (if configured)

**Default: 2,000ms (2 seconds)**

### Read Timeout (`readTimeoutMillis`)

Idle timeout for an established connection. If no frames are received within this window:
- Connection is terminated with `IdleTimeoutException`
- `ConnectionFailureReason.StreamFailure` is reported
- Reconnection policy is consulted (if configured)

**Default: 15,000ms (15 seconds)**

Set to `0` to disable idle timeout (not recommended for production).

## Observing Connection State

### ConnectionSnapshot

```kotlin
runtime.connectionSnapshots.collect { snapshot ->
    when (snapshot) {
        is ConnectionSnapshot.Disconnected -> println("Not connected")
        is ConnectionSnapshot.Connecting -> println("Connecting to ${snapshot.url}...")
        is ConnectionSnapshot.Connected -> println("Connected to ${snapshot.url}")
        is ConnectionSnapshot.Disconnecting -> println("Disconnecting...")
        is ConnectionSnapshot.Failed -> println("Failed: ${snapshot.message}")
    }
}
```

### ConnectionTelemetry

Extended connection information including retry state:

```kotlin
runtime.connectionTelemetry.collect { telemetry ->
    println("Snapshot: ${telemetry.snapshot}")
    println("Attempt #${telemetry.attempt}")
    println("Is retrying: ${telemetry.isRetrying}")
    telemetry.lastFailure?.let { failure ->
        println("Last failure: ${failure.message} (${failure.reason})")
    }
}
```

## Best Practices

### For Interactive Apps (wallets, chat clients)

Use aggressive reconnection for responsive UX:

```kotlin
reconnectionPolicy = ExponentialBackoffPolicy(
    baseDelayMillis = 200L,
    maxDelayMillis = 5_000L,
    maxAttempts = null  // Keep trying indefinitely
)
```

### For Background Services

Use conservative reconnection to avoid battery drain:

```kotlin
reconnectionPolicy = ExponentialBackoffPolicy(
    baseDelayMillis = 1_000L,
    maxDelayMillis = 60_000L,
    maxAttempts = 20
)
```

### For One-Shot Operations

Disable auto-reconnect and handle failures explicitly:

```kotlin
reconnectionPolicy = NoReconnectionPolicy

// Handle connection state manually
runtime.connectionSnapshots.first { it is ConnectionSnapshot.Failed }
    .let { /* show error to user, offer manual retry */ }
```

## Testing

Run the test suite:

```bash
./gradlew :nostr-runtime-coroutines:allTests
```

Tests cover:
- Connection lifecycle (connect, disconnect, reconnect)
- Timeout handling (handshake and idle)
- Reconnection policies (exponential backoff, max retries, user cancellation)
- Race conditions and edge cases
