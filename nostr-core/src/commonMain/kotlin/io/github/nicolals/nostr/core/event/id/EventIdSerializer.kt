package io.github.nicolals.nostr.core.event.id

import io.github.nicolals.nostr.core.event.EventKind
import io.github.nicolals.nostr.core.event.EventTags
import io.github.nicolals.nostr.core.types.HexKey
import okio.Buffer
import okio.ByteString

private fun Buffer.writeNip01JsonString(s: String) {
    writeByte('"'.code)

    var runStart = 0
    val n = s.length

    for (i in 0 until n) {
        val ch = s[i]
        val needsEscape = when (ch) {
            '\n', '"', '\\', '\r', '\t', '\b', '\u000C' -> true
            else -> false
        }
        if (!needsEscape) continue

        // Write the unescaped run since the last escape.
        if (runStart < i) writeUtf8(s, runStart, i)

        // Write the escape sequence (exactly as in NIP-01).
        when (ch) {
            '\n' -> writeUtf8("\\n")
            '"' -> writeUtf8("\\\"")
            '\\' -> writeUtf8("\\\\")
            '\r' -> writeUtf8("\\r")
            '\t' -> writeUtf8("\\t")
            '\b' -> writeUtf8("\\b")
            '\u000C' -> writeUtf8("\\f")
        }

        runStart = i + 1
    }

    // Tail run.
    if (runStart < n) writeUtf8(s, runStart, n)

    writeByte('"'.code)
}

private fun Buffer.writeTags(tags: EventTags) {
    writeByte('['.code)

    // If EventTags is List-like, this avoids iterator allocations.
    for (i in 0 until tags.size) {
        if (i > 0) writeByte(','.code)
        val tag = tags[i]
        writeByte('['.code)

        for (j in 0 until tag.size) {
            if (j > 0) writeByte(','.code)
            writeNip01JsonString(tag[j])
        }

        writeByte(']'.code)
    }

    writeByte(']'.code)
}

object EventIdSerializer {
    // Fast-path for internal usage without intermediate ByteString (hash buffer directly).
    internal fun nip01SerializeToBuffer(
        pubkey: HexKey,
        createdAt: Long,
        kind: EventKind,
        tags: EventTags,
        content: String,
    ): Buffer {
        val b = Buffer()
        b.writeByte('['.code)
        b.writeByte('0'.code)
        b.writeByte(','.code)
        b.writeNip01JsonString(pubkey)
        b.writeByte(','.code)
        b.writeDecimalLong(createdAt)
        b.writeByte(','.code)
        b.writeDecimalLong(kind.toLong())
        b.writeByte(','.code)
        b.writeTags(tags)
        b.writeByte(','.code)
        b.writeNip01JsonString(content)
        b.writeByte(']'.code)
        return b
    }

    fun nip01Serialize(
        pubkey: HexKey,
        createdAt: Long,
        kind: EventKind,
        tags: EventTags,
        content: String,
    ): ByteString {
        val b = nip01SerializeToBuffer(pubkey, createdAt, kind, tags, content)
        return b.snapshot()
    }
}
