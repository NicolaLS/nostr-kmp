package io.github.nicolals.nostr.bench.relay

import io.github.nicolals.nostr.bench.EventPayloadFactory
import io.github.nicolals.nostr.bench.EventStreamPlan
import io.github.nicolals.nostr.bench.RelayEdgeCases
import io.github.nicolals.nostr.bench.Scenario
import io.github.nicolals.nostr.bench.TimedDisconnect
import io.github.nicolals.nostr.bench.TimedFrame
import io.github.nicolals.nostr.bench.TimedNotice
import io.github.nicolals.nostr.bench.intervalNs
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RelaySimServer(private val scenario: Scenario) {
    private val payloadFactory = EventPayloadFactory(scenario.seed, scenario.eventProfiles)
    private val eventJsonFactory = EventJsonFactory(
        seed = scenario.seed,
        profiles = scenario.eventProfiles,
        payloadFactory = payloadFactory,
        baseCreatedAt = System.currentTimeMillis() / 1000,
    )
    private val hub = RelayHub()

    fun start(wait: Boolean) {
        embeddedServer(
            Netty,
            host = scenario.relay.host,
            port = scenario.relay.port,
        ) {
            configureRouting(this)
        }.start(wait = wait)
    }

    private fun configureRouting(application: Application) {
        application.install(WebSockets)

        application.routing {
            webSocket(scenario.relay.path) {
                val context = ConnectionContext(
                    session = this,
                    scenario = scenario,
                    eventJsonFactory = eventJsonFactory,
                    hub = hub,
                )
                context.handle()
            }
        }
    }
}

private class ConnectionContext(
    private val session: WebSocketSession,
    private val scenario: Scenario,
    private val eventJsonFactory: EventJsonFactory,
    private val hub: RelayHub,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sendMutex = Mutex()
    private val subscriptions = ConcurrentHashMap<String, SubscriptionState>()
    private val random = Random(scenario.seed)

    suspend fun handle() {
        val scope = session
        scheduleNotices(scope, scenario.relayBehavior.notices)
        scheduleDisconnects(scope, scenario.relayBehavior.disconnects)
        scheduleMalformed(scope, scenario.relayBehavior.edgeCases.malformedFrames)
        scheduleUnknownSubMessages(scope, scenario.relayBehavior.edgeCases)

        hub.register(this)
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    handleClientMessage(frame.readText(), scope)
                }
            }
        } finally {
            subscriptions.values.forEach { it.cancelAll() }
            subscriptions.clear()
            hub.unregister(this)
        }
    }

    private suspend fun handleClientMessage(text: String, scope: CoroutineScope) {
        val message = parseClientMessage(text) ?: return
        when (message) {
            is ClientMessage.Req -> handleReq(message, scope)
            is ClientMessage.Close -> handleClose(message, scope)
            is ClientMessage.Event -> handlePublish(message, scope)
        }
    }

    private fun handleReq(message: ClientMessage.Req, scope: CoroutineScope) {
        val existing = subscriptions.remove(message.subscriptionId)
        existing?.cancelAll()

        val state = SubscriptionState(message.subscriptionId, message.filters)
        subscriptions[message.subscriptionId] = state

        val behavior = scenario.relayBehavior.subscription
        if (behavior.sendClosedOnReq) {
            val closeJob = scope.launch {
                delay(behavior.closedDelayMs)
                if (!state.closed) {
                    state.closed = true
                    sendClosed(message.subscriptionId, behavior.closedMessage)
                    state.cancelAll()
                }
            }
            state.jobs += closeJob
        }

        val flowJob = scope.launch {
            behavior.stored?.let { plan ->
                sendStream(
                    plan = plan,
                    subId = message.subscriptionId,
                    phase = plan.label,
                    state = state,
                    ignoreClosed = false,
                )
            }

            if (behavior.sendEose) {
                delay(behavior.eoseDelayMs)
                sendEose(message.subscriptionId)
            }

            behavior.live?.let { plan ->
                sendStream(
                    plan = plan,
                    subId = message.subscriptionId,
                    phase = plan.label,
                    state = state,
                    ignoreClosed = false,
                )
            }
        }

        state.jobs += flowJob
    }

    private fun handleClose(message: ClientMessage.Close, scope: CoroutineScope) {
        val state = subscriptions[message.subscriptionId] ?: return
        state.closed = true
        state.cancelAll()

        val edges = scenario.relayBehavior.edgeCases
        edges.eoseAfterCloseMs?.let { delayMs ->
            scope.launch {
                delay(delayMs)
                sendEose(message.subscriptionId)
            }
        }

        edges.eventAfterClose?.let { plan ->
            scope.launch {
                delay(plan.startDelayMs)
                sendStream(
                    plan = plan,
                    subId = message.subscriptionId,
                    phase = plan.label,
                    state = state,
                    ignoreClosed = true,
                )
            }
        }
    }

    private fun handlePublish(message: ClientMessage.Event, scope: CoroutineScope) {
        val behavior = scenario.relayBehavior.publish
        scope.launch {
            if (behavior.okDelayMs > 0) {
                delay(behavior.okDelayMs)
            }
            val accepted = random.nextDouble() <= behavior.acceptRate
            val response = okMessage(
                eventId = message.event.id,
                accepted = accepted,
                message = if (accepted) behavior.acceptedMessage else behavior.rejectedMessage,
            )
            sendText(response)
        }

        if (scenario.relayBehavior.forward.enabled) {
            scope.launch {
                hub.forward(message.event)
            }
        }
    }

    suspend fun forwardEvent(event: PublishedEvent) {
        val forward = scenario.relayBehavior.forward
        if (!forward.enabled) return
        if (forward.kinds != null && event.kind !in forward.kinds) return

        val targets = subscriptions.values.filter { !it.closed }
        for (state in targets) {
            if (!matchesAny(state.filters, event)) continue
            forwardToSubscription(state.id, event, forward)
        }
    }

    private suspend fun sendStream(
        plan: EventStreamPlan,
        subId: String,
        phase: String,
        state: SubscriptionState,
        ignoreClosed: Boolean,
    ) {
        if (plan.startDelayMs > 0) {
            delay(plan.startDelayMs)
        }

        val events = eventJsonFactory.eventsFor(plan, phase)
        if (events.isEmpty()) return

        val intervalNs = max(0L, plan.intervalNs())
        val batchSize = max(1, plan.batchSize)
        var nextSendAt = System.nanoTime()

        for (i in events.indices) {
            if (!ignoreClosed && state.closed) return

            val message = eventMessage(subId, events[i])
            sendText(message)

            if (intervalNs > 0) {
                nextSendAt += intervalNs
                if (i % batchSize == 0) {
                    val now = System.nanoTime()
                    val delayNs = nextSendAt - now
                    if (delayNs > 0) {
                        delay(delayNs / 1_000_000L)
                    }
                }
            }
        }
    }

    private suspend fun scheduleNotices(scope: CoroutineScope, notices: List<TimedNotice>) {
        notices.forEach { notice ->
            scope.launch {
                delay(notice.delayMs)
                sendText(noticeMessage(notice.message))
            }
        }
    }

    private suspend fun scheduleDisconnects(scope: CoroutineScope, disconnects: List<TimedDisconnect>) {
        disconnects.forEach { item ->
            scope.launch {
                delay(item.delayMs)
                session.close(CloseReason(item.code.toShort(), item.reason))
            }
        }
    }

    private suspend fun scheduleMalformed(scope: CoroutineScope, frames: List<TimedFrame>) {
        frames.forEach { frame ->
            scope.launch {
                delay(frame.delayMs)
                sendText(frame.payload)
            }
        }
    }

    private suspend fun scheduleUnknownSubMessages(scope: CoroutineScope, edgeCases: RelayEdgeCases) {
        val unknownSubId = "unknown-${random.nextInt(1000, 9999)}"

        edgeCases.eventForUnknownSub?.let { plan ->
            scope.launch {
                delay(plan.startDelayMs)
                val state = SubscriptionState(unknownSubId)
                sendStream(
                    plan = plan,
                    subId = unknownSubId,
                    phase = plan.label,
                    state = state,
                    ignoreClosed = true,
                )
            }
        }

        edgeCases.eoseForUnknownSubDelayMs?.let { delayMs ->
            scope.launch {
                delay(delayMs)
                sendEose(unknownSubId)
            }
        }

        edgeCases.closedForUnknownSubDelayMs?.let { delayMs ->
            scope.launch {
                delay(delayMs)
                sendClosed(unknownSubId, "error: unknown subscription")
            }
        }
    }

    private suspend fun sendText(text: String) {
        sendMutex.withLock {
            session.send(Frame.Text(text))
        }
    }

    private suspend fun sendEose(subscriptionId: String) {
        sendText(eoseMessage(subscriptionId))
    }

    private suspend fun sendClosed(subscriptionId: String, message: String) {
        sendText(closedMessage(subscriptionId, message))
    }

    private fun parseClientMessage(text: String): ClientMessage? {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val array = element as? JsonArray ?: return null
        if (array.isEmpty()) return null
        val token = array[0].jsonPrimitive.content
        return when (token) {
            "REQ" -> {
                val subId = array.getOrNull(1)?.jsonPrimitive?.content ?: return null
                val filters = parseFilters(array)
                ClientMessage.Req(subId, filters)
            }
            "CLOSE" -> {
                val subId = array.getOrNull(1)?.jsonPrimitive?.content ?: return null
                ClientMessage.Close(subId)
            }
            "EVENT" -> {
                val eventObj = array.getOrNull(1) as? JsonObject ?: return null
                val event = parseEvent(eventObj) ?: return null
                ClientMessage.Event(event)
            }
            else -> null
        }
    }

    private fun parseFilters(array: JsonArray): List<RelayFilter> {
        if (array.size <= 2) return emptyList()
        val filters = mutableListOf<RelayFilter>()
        for (i in 2 until array.size) {
            val obj = array[i] as? JsonObject ?: continue
            parseFilter(obj)?.let { filters += it }
        }
        return filters
    }

    private fun parseFilter(obj: JsonObject): RelayFilter? {
        val ids = obj["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
        val authors = obj["authors"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
        val kinds = obj["kinds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }?.toSet()
        val since = obj["since"]?.jsonPrimitive?.longOrNull
        val until = obj["until"]?.jsonPrimitive?.longOrNull

        val tagFilters = obj.entries.mapNotNull { (key, value) ->
            val tagKey = when {
                key.startsWith("#") -> key.drop(1)
                key.length == 1 -> key
                else -> null
            }
            if (tagKey == null) return@mapNotNull null
            val values = (value as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
            if (values == null) null else tagKey to values
        }.toMap().takeIf { it.isNotEmpty() }

        return RelayFilter(
            ids = ids?.takeIf { it.isNotEmpty() },
            authors = authors?.takeIf { it.isNotEmpty() },
            kinds = kinds?.takeIf { it.isNotEmpty() },
            tagFilters = tagFilters,
            since = since,
            until = until,
        )
    }

    private fun parseEvent(obj: JsonObject): PublishedEvent? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val pubkey = obj["pubkey"]?.jsonPrimitive?.contentOrNull ?: return null
        val kind = obj["kind"]?.jsonPrimitive?.intOrNull ?: return null
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull
        val tags = obj["tags"]?.jsonArray?.mapNotNull { tagElement ->
            val tagArray = tagElement as? JsonArray ?: return@mapNotNull null
            val values = tagArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            values.takeIf { it.isNotEmpty() }
        } ?: emptyList()

        val rawJson = json.encodeToString(JsonObject.serializer(), obj)
        return PublishedEvent(
            id = id,
            pubkey = pubkey,
            kind = kind,
            createdAt = createdAt,
            tags = tags,
            rawJson = rawJson,
        )
    }

    private fun matchesAny(filters: List<RelayFilter>, event: PublishedEvent): Boolean {
        if (filters.isEmpty()) return true
        return filters.any { it.matches(event) }
    }

    private suspend fun forwardToSubscription(
        subId: String,
        event: PublishedEvent,
        forward: io.github.nicolals.nostr.bench.RelayForwarding,
    ) {
        val forwardRandom = forwardingRandom(subId, event.id)
        if (forward.dropRate > 0 && forwardRandom.nextDouble() < forward.dropRate) return
        val duplicate = forward.duplicateRate > 0 && forwardRandom.nextDouble() < forward.duplicateRate
        val sends = if (duplicate) 2 else 1

        repeat(sends) {
            val delayMs = when {
                forward.maxDelayMs > forward.minDelayMs -> {
                    val range = forward.maxDelayMs - forward.minDelayMs + 1
                    forward.minDelayMs + forwardRandom.nextLong(range)
                }
                else -> forward.minDelayMs
            }
            if (delayMs > 0) delay(delayMs)
            sendText(eventMessage(subId, event.rawJson))
        }
    }

    private fun forwardingRandom(subId: String, eventId: String): Random {
        val seed = scenario.seed xor (subId.hashCode().toLong() shl 32) xor eventId.hashCode().toLong()
        return Random(seed)
    }

    private fun eoseMessage(subscriptionId: String): String {
        return "[\"EOSE\",${jsonString(subscriptionId)}]"
    }

    private fun closedMessage(subscriptionId: String, message: String): String {
        return "[\"CLOSED\",${jsonString(subscriptionId)},${jsonString(message)}]"
    }

    private fun noticeMessage(message: String): String {
        return "[\"NOTICE\",${jsonString(message)}]"
    }

    private fun okMessage(eventId: String, accepted: Boolean, message: String): String {
        return "[\"OK\",${jsonString(eventId)},$accepted,${jsonString(message)}]"
    }

    private fun eventMessage(subscriptionId: String, eventJson: String): String {
        return "[\"EVENT\",${jsonString(subscriptionId)},$eventJson]"
    }

    private fun jsonString(value: String): String {
        return json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))
    }
}

private data class SubscriptionState(
    val id: String,
    val filters: List<RelayFilter> = emptyList(),
    @Volatile var closed: Boolean = false,
    val jobs: MutableList<Job> = mutableListOf(),
) {
    fun cancelAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
}

private sealed interface ClientMessage {
    data class Req(val subscriptionId: String, val filters: List<RelayFilter>) : ClientMessage
    data class Close(val subscriptionId: String) : ClientMessage
    data class Event(val event: PublishedEvent) : ClientMessage
}

private data class RelayFilter(
    val ids: Set<String>? = null,
    val authors: Set<String>? = null,
    val kinds: Set<Int>? = null,
    val tagFilters: Map<String, Set<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
) {
    fun matches(event: PublishedEvent): Boolean {
        ids?.let { if (event.id !in it) return false }
        authors?.let { if (event.pubkey !in it) return false }
        kinds?.let { if (event.kind !in it) return false }
        since?.let { if (event.createdAt != null && event.createdAt < it) return false }
        until?.let { if (event.createdAt != null && event.createdAt > it) return false }
        tagFilters?.forEach { (key, values) ->
            if (!event.hasTagValue(key, values)) return false
        }
        return true
    }
}

private data class PublishedEvent(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val createdAt: Long?,
    val tags: List<List<String>>,
    val rawJson: String,
) {
    fun hasTagValue(tagName: String, values: Set<String>): Boolean {
        return tags.any { tag ->
            tag.isNotEmpty() && tag[0] == tagName && tag.drop(1).any { it in values }
        }
    }
}

private class RelayHub {
    private val mutex = Mutex()
    private val connections = mutableSetOf<ConnectionContext>()

    suspend fun register(context: ConnectionContext) {
        mutex.withLock { connections.add(context) }
    }

    suspend fun unregister(context: ConnectionContext) {
        mutex.withLock { connections.remove(context) }
    }

    suspend fun forward(event: PublishedEvent) {
        val snapshot = mutex.withLock { connections.toList() }
        snapshot.forEach { it.forwardEvent(event) }
    }
}
