# Client Lifecycle

`NwcClient` owns the relay session, response subscriptions, notification
subscriptions, request correlation, and wallet info state for one NWC
connection URI.

## Creating a Client

Use one of the companion factories:

```kotlin
val client = NwcClient(uri, scope)
```

```kotlin
val client = NwcClient.fromUri(connectionUriString, scope)
    ?: error("Invalid NWC URI")
```

```kotlin
val client = NwcClient.fromDeepLink(deepLinkUri, scope)
    ?: error("Invalid NWC deep link")
```

Each factory starts connection setup immediately by calling `connect()`.

## Connection Flow

Connection setup connects to all distinct relay URLs and becomes usable once at
least one relay is connected. It subscribes to responses, fetches authenticated
wallet info (kind `13194`), and subscribes to advertised notifications. Cached
wallet info allows readiness while fresh info is fetched in the background.

The client verifies the event ID and signature before trusting any incoming
event. It also checks the configured wallet author, response/notification
recipient, and request correlation locally. Relay filters are routing hints;
they do not establish the sender's identity.

Connection initialization belongs to a child job of the supplied scope. A
forced reconnect cancels the previous initialization and closes its pool.
`close()` permanently closes this client, including in-progress initialization;
create a new client to start another lifecycle.

## Injecting Capabilities

The convenience constructor uses local signing and encryption from the URI,
the Kotlinx codec, and the default Ktor transport with WebSockets installed.
Applications can supply `transportFactory`, `codec`, `signer`, `verifier`, and
`cipher` independently:

```kotlin
val connection = NwcConnection(
    walletPubkey = walletPubkey,
    clientPubkey = accountSigner.pubkey,
    relays = relayUrls,
)
val client = NwcClient(
    connection = connection,
    scope = scope,
    transportFactory = transportFactory,
    codec = codec,
    signer = accountSigner,
    verifier = eventVerifier,
    cipher = Nip47Cipher(accountNip04Cipher, accountNip44Cipher),
)
```

This factory requires only public connection details; private keys can remain in
a remote service or hardware. `EventTemplateSigner` and encryption operations
suspend for I/O or confirmation. The signer and ciphers must represent
`connection.clientPubkey`; the verifier must authenticate both the event ID and
signature. `client.connection` is always available. `client.uri` is null for
this factory and contains the credential-bearing URI for the local convenience
factory.

The client closes its internally created transport factory. Injected factories
and HTTP clients remain caller-owned. Supply either `httpClient` or
`transportFactory`, never both. Cancelling the parent scope stops client work;
`close()` also waits for cleanup and is safe to call from a cancelled coroutine.

For additional wallet methods, send `NwcUnknownRequest` through
`client.request(request)`. `walletInfo.value.supports(methodName)` and
`capabilityStrings` preserve advertised extension names.

## State

The client exposes:

```kotlin
val state: StateFlow<NwcClientState>
```

Possible states:

- `Disconnected`: no active relay session.
- `Connecting`: relay connection and subscriptions are being established.
- `Ready`: requests can be sent.
- `Closed`: `close()` was called or the parent scope was cancelled.
- `Failed`: connection setup failed with a typed `NwcError`.

Observe state from UI code:

```kotlin
scope.launch {
    client.state.collect { state ->
        when (state) {
            NwcClientState.Disconnected -> println("Disconnected")
            NwcClientState.Connecting -> println("Connecting")
            NwcClientState.Ready -> println("Ready")
            NwcClientState.Closed -> println("Closed")
            is NwcClientState.Failed -> println(state.error.message)
        }
    }
}
```

## Readiness

Use `awaitReady` when your workflow needs an explicit connection gate:

```kotlin
val ready = client.awaitReady(timeoutMs = 10_000)
if (!ready) {
    // Show setup error or retry.
}
```

Wallet operations also call the client's internal connection guard. If the
session is disconnected, the operation attempts to reconnect within its timeout
budget before publishing the request.

## Reconnection and In-Flight Requests

NWC request and response events are ephemeral. If the relay connection drops
while a request is in flight, the response may not be replayed after reconnect.
The client therefore fails pending requests instead of assuming they can be
recovered.

For outgoing payments, `payInvoice` defaults to `verifyOnTimeout = true`. If the
payment request times out, the client performs `lookup_invoice` for the invoice:

- A settled transaction becomes a successful `PaymentResult`.
- A pending transaction becomes `NwcError.PaymentPending`.
- A failed, expired, missing, or unverifiable transaction becomes a timeout.

For app restart recovery, persist the payment hash when you have one and call:

```kotlin
val status = client.checkPaymentStatus(paymentHash)
```

## Wallet Info

The client exposes:

```kotlin
val walletInfo: StateFlow<WalletInfo>
```

Before the wallet info event is fetched, the StateFlow contains
`WalletInfo.default()`. That default has no capabilities or notifications and
uses NIP-04 for backwards compatibility.

After connection, `walletInfo` reflects the wallet info event:

```kotlin
val info = client.walletInfo.value
if (info.supports(NwcCapability.PAY_INVOICE)) {
    println("Payments are supported")
}
```

Refresh wallet info manually:

```kotlin
when (val result = client.refreshWalletInfo()) {
    is NwcResult.Success -> println(result.value.capabilityStrings)
    is NwcResult.Failure -> println(result.error.message)
}
```

## Encryption

The client supports NIP-04 and NIP-44 v2 through `nostr-kmp`.

Selection is driven by wallet info:

- Prefer NIP-44 v2 when advertised.
- Use NIP-04 when advertised and NIP-44 is not available.
- Default to NIP-04 when the wallet does not advertise encryption support.

Inspect the current choice:

```kotlin
val encryption = client.encryption
```

If `walletInfo.value.encryptionDefaultedToNip04` is true, the wallet did not
publish an encryption tag in its info event.

## Notifications

Notifications are exposed as a `SharedFlow`:

```kotlin
val notifications: SharedFlow<WalletNotification>
```

Collect them while the client is alive:

```kotlin
scope.launch {
    client.notifications.collect { notification ->
        when (notification) {
            is WalletNotification.PaymentReceived -> {
                println("Received ${notification.transaction.amount}")
            }
            is WalletNotification.PaymentSent -> {
                println("Sent ${notification.transaction.amount}")
            }
        }
    }
}
```

Notification collection only receives events emitted while subscribed. It has no
replay cache. Use `listTransactions` or `lookupInvoice` when you need durable
history.

## Closing

Call `close()` when the client is no longer needed:

```kotlin
client.close()
```

Closing cancels connection setup, relay subscriptions, and collector jobs, closes
the relay pool, and terminates pending requests. It leaves the caller's scope and
injected transport resources under caller ownership. A closed client cannot
reconnect. Cancellation from an operation's caller propagates as coroutine
cancellation; it is not converted into a wallet error.

## Request Deadlines

A request's positive `timeoutMs` covers connection readiness, remote encryption
and signing, relay acknowledgment, and the wallet response. Cancelling the caller
cancels that operation without closing the client. Closing or replacing the
connection cancels outstanding remote approvals, and publication checks the
connection generation before sending. The signed expiration is derived from the
initial request deadline, with five seconds of clock-skew allowance.

`discover` uses one deadline across readiness and `get_info`; resource cleanup
still completes when the deadline or caller cancellation occurs. `payInvoice`
may perform the separately documented verification lookup after its request times
out when `verifyOnTimeout` is enabled.
