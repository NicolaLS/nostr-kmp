# NostrClient Usage Guide

High-level Nostr client with automatic connection management, NIP-42 authentication, and relay management.

## Quick Start

```kotlin
// Create client with your signer
val client = NostrClient.create(signer)

// Add relays
client.addRelay("wss://relay.damus.io")
client.addRelay("wss://nos.lol")

// Publish
client.publish(event)

// Query
val result = client.query(listOf(Filter(kinds = listOf(1), limit = 10)))

// Subscribe
client.subscribe(filters).collect { event -> println(event.content) }

// Cleanup
client.shutdown()
```

## Client Creation

```kotlin
// With existing signer (recommended)
val client = NostrClient.create(signer)

// Auto-generated keypair (prototyping)
val client = NostrClient.create()

// Ephemeral mode - new keypair per publish (max privacy)
val client = NostrClient.ephemeral()

// Custom configuration
val client = NostrClient.create(NostrClientConfig(
    signer = signer,
    defaultTimeoutMillis = 60_000,
    connectTimeoutMillis = 15_000,
    maxRetries = 5
))
```

## Identity

```kotlin
// Get public key (hex-encoded, 64 characters)
val pubkey = client.publicKey

// Safe access for ephemeral clients
val pubkey = client.publicKeyOrNull  // null for ephemeral

// Check if ephemeral mode
if (client.isEphemeral) {
    // No stable identity - new keypair per publish
}
```

Note: Private keys are intentionally not exposed. Keep a reference to your `KeyPair` if you need the private key.

## Relay Management

```kotlin
// Add relays (URLs are normalized - trailing slashes stripped)
client.addRelay("wss://relay.damus.io")
client.addRelay("wss://nos.lol")

// Check managed relays
client.relays  // Set<String>

// Remove relay
client.removeRelay("wss://nos.lol")
```

All operations automatically use managed relays. When you specify an explicit URL, it's **added** to the managed set for that operation.

## Publishing

```kotlin
// Fire and forget
client.publish(event)

// Wait for all acknowledgments
val handle = client.publish(event)
val results = handle.awaitAll(timeoutMillis = 5000)

// Wait for first acknowledgment
val (url, result) = handle.awaitFirst(timeoutMillis = 3000) ?: return

// Wait for specific relay
val result = handle.await("wss://relay.damus.io", timeoutMillis = 5000)

// Check status without waiting
handle.confirmedRelays  // Relays that responded
handle.acceptedRelays   // Relays that accepted
handle.rejectedRelays   // Relays that rejected
handle.pendingRelays    // Relays still waiting
handle.isComplete       // All responded?
handle.hasAccepted      // At least one accepted?

// Publish to specific relay + managed relays
client.publish("wss://relay.nostr.band", event)
```

## Querying

```kotlin
// Query all managed relays (results deduplicated)
when (val result = client.query(filters)) {
    is RequestResult.Success -> result.data.forEach { println(it) }
    is RequestResult.ConnectionFailed -> println(result.message)
    is RequestResult.Timeout -> println("Timed out")
}

// Query specific relay + managed relays
client.query("wss://purplepag.es", filters)

// Custom timeout
client.query(filters, timeoutMillis = 10_000)
```

## Subscribing

```kotlin
// Subscribe to all managed relays (events deduplicated)
client.subscribe(filters).collect { event ->
    println(event.content)
}

// Subscribe to specific relay + managed relays
client.subscribe("wss://relay.snort.social", filters).collect { ... }

// With cancellation
val job = scope.launch {
    client.subscribe(filters).collect { ... }
}
job.cancel()
```

## Request-Response Pattern

For operations expecting a single response (NIP-04 DMs, NWC, etc.):

```kotlin
when (val result = client.request(url, filters)) {
    is RequestResult.Success -> println(result.data?.content)
    is RequestResult.Timeout -> println("No response")
    is RequestResult.ConnectionFailed -> println("Failed")
}
```

## Connection State

```kotlin
client.connectionState("wss://relay.damus.io").collect { state ->
    when (state) {
        ConnectionState.Disconnected -> println("Disconnected")
        ConnectionState.Connecting -> println("Connecting...")
        ConnectionState.Connected -> println("Connected!")
        is ConnectionState.Failed -> println("Failed: ${state.error}")
    }
}
```

## Operation Behavior Summary

| Operation | Uses Managed Relays | Explicit URL | Notes |
|-----------|:------------------:|:------------:|-------|
| `query(filters)` | Yes | - | Deduplicated results |
| `query(url, filters)` | Yes | Added | Deduplicated results |
| `publish(event)` | Yes | - | Returns PublishHandle |
| `publish(url, event)` | Yes | Added | Returns PublishHandle |
| `subscribe(filters)` | Yes | - | Deduplicated events |
| `subscribe(url, filters)` | Yes | Added | Deduplicated events |
| `request(url, filters)` | No | Only | Uses shared subscription pool |
