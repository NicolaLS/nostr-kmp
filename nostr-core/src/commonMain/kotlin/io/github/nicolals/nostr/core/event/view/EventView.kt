package io.github.nicolals.nostr.core.event.view

import io.github.nicolals.nostr.core.event.Event

/**
 * A typed view over a signed [Event].
 *
 * ## Performance contract
 * Typed views MUST be cheap to construct. Do not decrypt/parse in constructors.
 *
 * - Prefer lazy parsing:
 *   `private val payload by lazy(LazyThreadSafetyMode.NONE) { ... }`
 * - Or, if you already have known/parsed values (e.g., from a template builder or upgrader),
 *   store them privately and have properties read from known values first.
 *
 * Note: If you need to store cached state, use a normal class. Value classes cannot store caches.
 *
 * Example:
 * ```kotlin
 * data class NwcPayKnown(val amount: Long, val description: String?)
 *
 * class NwcPayReq(
 *     override val event: Event,
 *     private val known: NwcPayKnown? = null,
 *     private val decryptor: Decryptor? = null,
 * ) : EventView {
 *
 *     private val parsed by lazy(LazyThreadSafetyMode.NONE) {
 *         requireNotNull(decryptor) { "Decryptor required to parse NWC payload." }
 *         decryptAndParse(event.content, decryptor)
 *     }
 *
 *     val amount: Long get() = known?.amount ?: parsed.amount
 *     val description: String? get() = known?.description ?: parsed.description
 * }
 *
 * ```
 */
interface EventView {
    val event: Event
}
