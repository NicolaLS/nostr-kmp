package io.github.nicolals.nostr.core.protocol

/**
 * Key for module-specific state in [ModuleStateStore].
 *
 * NIP modules define their own state keys to store custom state
 * without modifying [ProtocolState].
 *
 * ## Example
 *
 * ```kotlin
 * // In NIP-42 module
 * data class Nip42AuthState(
 *     val phase: AuthPhase = AuthPhase.Unauthenticated,
 *     val challenge: String? = null,
 * )
 *
 * object Nip42StateKey : ModuleStateKey<Nip42AuthState> {
 *     override val id = "nip42.auth"
 *     override val default = Nip42AuthState()
 * }
 *
 * // In reducer
 * val authState = state.moduleStates[Nip42StateKey]
 * val newModuleStates = state.moduleStates.set(Nip42StateKey, newAuthState)
 * ```
 *
 * @param T The type of state stored under this key.
 */
interface ModuleStateKey<T : Any> {
    /**
     * Unique identifier for this state key.
     *
     * Should be namespaced to avoid collisions (e.g., "nip42.auth").
     */
    val id: String

    /**
     * Default value when no state has been set.
     */
    val default: T
}

/**
 * Immutable store for module-specific state.
 *
 * This allows NIP modules to extend [ProtocolState] with custom state
 * without modifying the core state structure. Each module defines its
 * own [ModuleStateKey] to access its state.
 *
 * The store is immutable; [set] returns a new store with the updated value.
 */
class ModuleStateStore(
    private val map: Map<String, Any> = emptyMap(),
) {
    /**
     * Gets the state for the given key, or the key's default if not set.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: ModuleStateKey<T>): T =
        (map[key.id] as? T) ?: key.default

    /**
     * Returns a new store with the given key set to the given value.
     */
    fun <T : Any> set(key: ModuleStateKey<T>, value: T): ModuleStateStore =
        ModuleStateStore(map + (key.id to value))

    /**
     * Returns a new store with the given key removed.
     */
    fun remove(key: ModuleStateKey<*>): ModuleStateStore =
        ModuleStateStore(map - key.id)

    /**
     * Checks if state exists for the given key.
     */
    fun contains(key: ModuleStateKey<*>): Boolean =
        key.id in map

    override fun equals(other: Any?): Boolean =
        other is ModuleStateStore && map == other.map

    override fun hashCode(): Int = map.hashCode()

    override fun toString(): String = "ModuleStateStore($map)"
}
