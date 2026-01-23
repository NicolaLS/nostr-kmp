package io.github.nicolals.nostr.bench

object BenchTags {
    const val TAG = "bench"
    const val KEY_PHASE = "phase"
    const val KEY_SEQ = "seq"
    const val KEY_OFFSET_NS = "offset_ns"

    fun benchTags(phase: String, seq: Long, offsetNs: Long): List<List<String>> {
        return listOf(
            listOf(TAG, KEY_PHASE, phase),
            listOf(TAG, KEY_SEQ, seq.toString()),
            listOf(TAG, KEY_OFFSET_NS, offsetNs.toString()),
        )
    }

    fun findValue(tags: List<List<String>>, key: String): String? {
        return tags.firstOrNull { tag ->
            tag.size >= 3 && tag[0] == TAG && tag[1] == key
        }?.getOrNull(2)
    }

    fun phase(tags: List<List<String>>): String? = findValue(tags, KEY_PHASE)

    fun offsetNs(tags: List<List<String>>): Long? = findValue(tags, KEY_OFFSET_NS)?.toLongOrNull()
}
