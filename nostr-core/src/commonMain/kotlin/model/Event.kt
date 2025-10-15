package nostr.core.model

import nostr.core.Nip01Canonical
import nostr.core.crypto.Sha256
import nostr.core.crypto.Signer
import nostr.core.crypto.event.EventId
import nostr.core.time.Clock
import nostr.core.time.SystemClock
import nostr.core.utils.isHex
import nostr.core.utils.isLowercaseHex
import nostr.core.utils.toHexLower

/**
 * A signed Nostr event as defined by NIP-01.
 */
data class Event(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    init {
        require(id.isLowercaseHex(64)) { "event id must be 64 lowercase hex characters" }
        require(pubkey.isLowercaseHex(64)) { "pubkey must be 64 lowercase hex characters" }
        require(kind in 0..KIND_MAX) { "event kind must be between 0 and $KIND_MAX" }
        require(sig.isLowercaseHex(128)) { "signature must be 128 lowercase hex characters" }
        requireValidTags(tags)
    }

    fun toUnsigned(): UnsignedEvent = UnsignedEvent(pubkey, createdAt, kind, tags, content)

    fun canonicalFields(): Nip01Canonical.EventFields = toUnsigned().canonicalFields()

    fun recomputeId(hasher: Sha256 = Sha256.Default): String =
        computeIdFromCanonical(hasher, canonicalFields())

    fun verifyId(hasher: Sha256 = Sha256.Default): Boolean = recomputeId(hasher) == id

    companion object {
        const val KIND_MAX: Int = 65535

        fun fromUnsigned(unsigned: UnsignedEvent, signatureHex: String, hasher: Sha256 = Sha256.Default): Event {
            require(signatureHex.isHex(128)) { "signature must be 128 hex characters" }
            val computedId = computeIdFromCanonical(hasher, unsigned.canonicalFields())
            return Event(
                id = computedId,
                pubkey = unsigned.pubkey,
                createdAt = unsigned.createdAt,
                kind = unsigned.kind,
                tags = unsigned.tags,
                content = unsigned.content,
                sig = signatureHex.lowercase()
            )
        }
    }
}

/**
 * Unsigned Nostr event payload. Use [sign] or [EventBuilder] to turn it into a signed [Event].
 */
data class UnsignedEvent(
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String
) {
    init {
        require(pubkey.isLowercaseHex(64)) { "pubkey must be 64 lowercase hex characters" }
        require(kind in 0..Event.KIND_MAX) { "event kind must be between 0 and ${Event.KIND_MAX}" }
        requireValidTags(tags)
    }

    fun canonicalFields(): Nip01Canonical.EventFields = Nip01Canonical.EventFields(
        pubkeyHex32 = pubkey.lowercase(),
        createdAtSeconds = createdAt,
        kind = kind,
        tags = tags,
        content = content
    )

    fun computeId(hasher: Sha256 = Sha256.Default): String =
        computeIdFromCanonical(hasher, canonicalFields())

    fun sign(signer: Signer, hasher: Sha256 = Sha256.Default): Event {
        val canonicalBytes = Nip01Canonical.serializeEventArrayForSigning(canonicalFields())
        val eventId = EventId.fromByteArray(hasher.hash(canonicalBytes))
        val signature = signer.sign(eventId)
        return Event(
            id = eventId.toString(),
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = signature.toString()
        )
    }
}

internal fun computeIdFromCanonical(hasher: Sha256, fields: Nip01Canonical.EventFields): String {
    val canonicalBytes = Nip01Canonical.serializeEventArrayForSigning(fields)
    return hasher.hash(canonicalBytes).toHexLower()
}

internal fun requireValidTags(tags: List<List<String>>) {
    tags.forEachIndexed { index, tag ->
        requireValidTag(tag) { "event tag at index $index must contain at least one string per NIP-01" }
    }
}

internal fun requireValidTag(tag: List<String>, message: (() -> String)? = null) {
    require(tag.isNotEmpty()) {
        message?.invoke() ?: "event tag must contain at least one string per NIP-01"
    }
}

/**
 * Mutable builder used to assemble and sign events. Builders inherit the identity’s clock
 * and hasher, but you can provide custom values when constructing the builder directly for
 * advanced use cases (e.g., deterministic testing).
 */
class EventBuilder(
    private val clock: Clock? = SystemClock,
    private val hasher: Sha256 = Sha256.Default,
    private val protectSigner: Boolean = false
) {
    private var createdAt: Long? = null
    private var kind: Int? = null
    private var tags: List<List<String>> = emptyList()
    private var content: String? = null
    private var signer: Signer? = null

    fun createdAt(secondsUtc: Long) = apply { createdAt = secondsUtc }

    fun kind(value: Int) = apply {
        require(value in 0..Event.KIND_MAX) { "event kind must be between 0 and ${Event.KIND_MAX}" }
        kind = value
    }

    fun tags(tags: List<List<String>>) = apply {
        requireValidTags(tags)
        this.tags = tags
    }

    fun addTags(vararg newTags: List<String>) = apply {
        require(newTags.isNotEmpty()) { "addTags requires at least one tag" }
        val updated = tags + newTags
        requireValidTags(updated)
        this.tags = updated
    }

    fun addTag(tag: List<String>) = apply { addTags(tag) }

    fun addTag(vararg values: String) = apply {
        require(values.isNotEmpty()) { "addTag requires at least one value" }
        addTags(values.toList())
    }

    fun content(value: String) = apply { content = value }

    fun signer(signer: Signer) = apply {
        if (protectSigner && this.signer != null) {
            throw IllegalArgumentException("signer is protected")
        }
        this.signer = signer
    }

    fun build(): Event {
        val finalSigner = signer ?: throw IllegalStateException("missing signer")
        val finalKind = kind ?: throw IllegalStateException("missing kind")
        val finalCreatedAt = createdAt ?: clock?.nowSeconds() ?: throw IllegalStateException("missing time")
        val unsigned = UnsignedEvent(
            pubkey = finalSigner.publicKey.toString(),
            createdAt = finalCreatedAt,
            kind = finalKind,
            tags = tags,
            content = content ?: ""
        )
        val canonicalBytes = Nip01Canonical.serializeEventArrayForSigning(unsigned.canonicalFields())
        val eventId = EventId.fromByteArray(hasher.hash(canonicalBytes))
        val signature = finalSigner.sign(eventId)
        return Event(
            id = eventId.toString(),
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = signature.toString()
        )
    }

    fun buildOrNull(): Event? = runCatching { build() }.getOrNull()

    fun buildResult(): Result<Event> = runCatching { build() }
}
