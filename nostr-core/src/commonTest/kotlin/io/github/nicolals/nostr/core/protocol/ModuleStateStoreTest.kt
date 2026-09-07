package io.github.nicolals.nostr.core.protocol

import kotlin.test.*

class ModuleStateStoreTest {
    private object NumberKey : ModuleStateKey<Int> {
        override val id = "test.state"
        override val default = 0
    }

    @Test fun conflictingKeysCannotReadOverwriteOrRemoveAnotherModulesState() {
        val other = object : ModuleStateKey<String> {
            override val id = NumberKey.id
            override val default = ""
        }
        val original = ModuleStateStore()
        val stored = original.set(NumberKey, 42)
        assertEquals(0, original[NumberKey])
        assertEquals(42, stored[NumberKey])
        assertFailsWith<IllegalArgumentException> { stored[other] }
        assertFailsWith<IllegalArgumentException> { stored.set(other, "private") }
        assertFailsWith<IllegalArgumentException> { stored.remove(other) }
        assertFailsWith<IllegalArgumentException> { stored.contains(other) }
        assertEquals(42, stored[NumberKey])
        assertEquals(0, stored.remove(NumberKey)[NumberKey])
    }
}
