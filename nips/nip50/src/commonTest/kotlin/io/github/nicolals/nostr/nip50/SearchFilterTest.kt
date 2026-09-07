package io.github.nicolals.nostr.nip50

import io.github.nicolals.nostr.codec.kotlinx.KotlinxNostrJsonCodec
import io.github.nicolals.nostr.core.message.Filter
import io.github.nicolals.nostr.core.message.upgrade.MessageUpgradeRegistry
import io.github.nicolals.nostr.core.nip.install
import io.github.nicolals.nostr.core.primitives.EventKind
import io.github.nicolals.nostr.core.primitives.SubscriptionId
import io.github.nicolals.nostr.nip01.Nip01Module
import io.github.nicolals.nostr.nip01.message.ReqClientMessage
import io.github.nicolals.nostr.nip45.Nip45Module
import io.github.nicolals.nostr.nip45.message.CountClientMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SearchFilterTest {
    private val codec = KotlinxNostrJsonCodec()
    private val registry = MessageUpgradeRegistry.Builder()
        .install(Nip01Module(codec))
        .install(Nip45Module(codec))
        .build()

    @Test
    fun searchSurvivesReqAndCountWithoutRegisteringANewCodec() {
        val filters = listOf(Filter(kinds = listOf(EventKind(1))).withSearch("bitcoin"))
        val id = SubscriptionId("search")
        val req = ReqClientMessage.create(id, filters, codec)
        val decodedReq = registry.upgradeClient(codec.decodeClientMessage(codec.encodeClientMessageString(req)))
        assertEquals(filters, assertIs<ReqClientMessage>(decodedReq).filters)

        val count = CountClientMessage.create(id, filters, codec)
        val decodedCount = registry.upgradeClient(codec.decodeClientMessage(codec.encodeClientMessageString(count)))
        assertEquals(filters, assertIs<CountClientMessage>(decodedCount).filters)
    }

    @Test
    fun removingSearchPreservesTheOtherConditions() {
        val filter = Filter(limit = 10)
        assertEquals("cats", filter.withSearch("cats").search)
        assertEquals(filter, filter.withSearch("cats").withSearch(null))
    }
}
