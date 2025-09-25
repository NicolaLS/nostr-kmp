package nostr.core.model

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FilterValidationTest {

    private val sampleId = "a".repeat(64)

    @Test
    fun `ids must be lowercase hex`() {
        assertFailsWith<IllegalArgumentException> {
            Filter(ids = setOf("F".repeat(64)))
        }
    }

    @Test
    fun `authors must be lowercase hex`() {
        assertFailsWith<IllegalArgumentException> {
            Filter(authors = setOf("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"))
        }
    }

    @Test
    fun `kinds must be within bounds`() {
        assertFailsWith<IllegalArgumentException> {
            Filter(kinds = setOf(Event.KIND_MAX + 1))
        }
    }

    @Test
    fun `tag keys must be single letter`() {
        assertFailsWith<IllegalArgumentException> {
            Filter(tags = mapOf("#ab" to setOf("value")))
        }
    }

    @Test
    fun `tag values cannot be empty`() {
        assertFailsWith<IllegalArgumentException> {
            Filter(tags = mapOf("#e" to setOf("")))
        }
    }

    @Test
    fun `valid filter passes`() {
        val filter = Filter(
            ids = setOf(sampleId),
            authors = setOf("b".repeat(64)),
            kinds = setOf(1),
            tags = mapOf("#e" to setOf(sampleId))
        )
        assertNotNull(filter)
    }
}
