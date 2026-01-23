package io.github.nicolals.nostr.bench

class CliArgs(private val args: Array<String>) {
    private val values: Map<String, String> = parse(args)

    fun get(key: String): String? = values[key]

    fun getOrNull(key: String): String? = values[key]

    fun require(key: String): String = values[key]
        ?: error("Missing required argument --$key")

    fun getLong(key: String): Long? = values[key]?.toLongOrNull()

    fun getInt(key: String): Int? = values[key]?.toIntOrNull()

    private fun parse(args: Array<String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg.startsWith("--")) {
                val key = arg.removePrefix("--")
                val value = if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                    i += 1
                    args[i]
                } else {
                    "true"
                }
                map[key] = value
            }
            i += 1
        }
        return map
    }
}
