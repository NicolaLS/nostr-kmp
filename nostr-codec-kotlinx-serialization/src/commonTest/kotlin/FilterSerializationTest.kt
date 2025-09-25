package nostr.codec.kotlinx.serialization

import kotlin.test.Test
import kotlin.test.assertEquals
import nostr.core.model.Filter

class FilterSerializationTest {

    private val codec = KotlinxSerializationWireCodec.default()

    @Test
    fun filterJsonRoundTrip() {
        val filter = Filter(
            ids = setOf("1".repeat(64), "2".repeat(64)),
            kinds = setOf(0, 1),
            authors = setOf("a".repeat(64)),
            since = 1,
            until = 2,
            limit = 20,
            tags = mapOf(
                "#e" to setOf("3".repeat(64)),
                "#p" to setOf("4".repeat(64))
            )
        )

        val encoded = codec.filter(filter)
        val decoded = codec.filter(encoded)

        assertEquals(filter.copy(tags = filter.tags.mapValues { it.value.toSet() }), decoded)
    }
}
