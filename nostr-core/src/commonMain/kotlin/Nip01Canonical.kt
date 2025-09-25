package nostr.core

/**
 * Canonical JSON serialization helpers per NIP-01. Every implementation must follow the
 * same escaping and ordering rules to ensure deterministic event IDs.
 */
object Nip01Canonical {

    /** Minimal representation of the fields required to produce the canonical array. */
    data class EventFields(
        val pubkeyHex32: String,
        val createdAtSeconds: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String
    )

    /*
     To prevent implementation differences from creating a different event ID for the same event,
     the following rules MUST be followed while serializing:
        - UTF-8 should be used for encoding.
        - Whitespace, line breaks or other unnecessary formatting should not be included in the
          output JSON.
        - The following characters in the content field must be escaped as shown, and all other
          characters must be included verbatim:
            - A line break (`0x0A`), use `\n`
            - A double quote (`0x22`), use `\"`
            - A backslash (`0x5C`), use `\\`
            - A carriage return (`0x0D`), use `\r`
            - A tab character (`0x09`), use `\t`
            - A backspace, (`0x08`), use `\b`
            - A form feed, (`0x0C`), use `\f`
     */
    fun serializeEventArrayForSigning(fields: EventFields): ByteArray {
        val sb = StringBuilder(256 + fields.content.length + fields.tags.size * 8)
        sb.append('[')
        sb.append('0')
        sb.append(',')
        appendJsonString(sb, fields.pubkeyHex32)
        sb.append(',')
        sb.append(fields.createdAtSeconds)
        sb.append(',')
        sb.append(fields.kind)
        sb.append(',')
        appendTags(sb, fields.tags)
        sb.append(',')
        appendJsonString(sb, fields.content)
        sb.append(']')
        return sb.toString().encodeToByteArray() // UTF-8
    }

    private fun appendTags(sb: StringBuilder, tags: List<List<String>>) {
        sb.append('[')
        var i = 0
        for (tag in tags) {
            if (i++ > 0) sb.append(',')
            sb.append('[')
            var j = 0
            for (s in tag) {
                if (j++ > 0) sb.append(',')
                appendJsonString(sb, s)
            }
            sb.append(']')
        }
        sb.append(']')
    }

    // Escape exactly: \n \" \\ \r \t \b \f ; everything else verbatim.
    private fun appendJsonString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\n' -> sb.append("\\n")
                '\"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f") // form-feed
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}
