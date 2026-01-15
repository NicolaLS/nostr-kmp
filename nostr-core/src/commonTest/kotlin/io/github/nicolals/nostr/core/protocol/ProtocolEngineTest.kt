package io.github.nicolals.nostr.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolEngineTest {

    // Test input for testing
    private data class TestInput(val value: String) : ProtocolInput

    // Test signal for testing
    private data class TestSignal(val value: String) : ProtocolSignal

    @Test
    fun engineWithNoReducersReturnsUnchangedState() {
        val engine = ProtocolEngine.Builder().build()
        val state = ProtocolState()

        val result = engine.process(state, TestInput("test"))

        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.signals.isEmpty())
    }

    @Test
    fun reducerThatReturnsNullIsSkipped() {
        val skippingReducer = object : ProtocolReducer {
            override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? = null
        }

        val engine = ProtocolEngine.Builder()
            .register(skippingReducer)
            .build()

        val state = ProtocolState()
        val result = engine.process(state, TestInput("test"))

        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.signals.isEmpty())
    }

    @Test
    fun reducerThatHandlesInputModifiesState() {
        val handlingReducer = object : ProtocolReducer {
            override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
                if (input is TestInput) {
                    val newState = state.withConnection(ConnectionState(ConnectionPhase.Open))
                    return ReduceResult.withSignals(newState, TestSignal(input.value))
                }
                return null
            }
        }

        val engine = ProtocolEngine.Builder()
            .register(handlingReducer)
            .build()

        val state = ProtocolState()
        val result = engine.process(state, TestInput("hello"))

        assertEquals(ConnectionPhase.Open, result.state.connection.phase)
        assertEquals(1, result.signals.size)
        assertEquals(TestSignal("hello"), result.signals[0])
    }

    @Test
    fun multipleReducersAreChainedinOrder() {
        var firstReducerCalled = false
        var secondReducerCalled = false

        val firstReducer = object : ProtocolReducer {
            override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
                firstReducerCalled = true
                return ReduceResult(
                    state = state.withConnection(ConnectionState(ConnectionPhase.Open)),
                    signals = listOf(TestSignal("first")),
                )
            }
        }

        val secondReducer = object : ProtocolReducer {
            override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
                secondReducerCalled = true
                // Verify we received the state from the first reducer
                assertEquals(ConnectionPhase.Open, state.connection.phase)
                return ReduceResult(
                    state = state,
                    signals = listOf(TestSignal("second")),
                )
            }
        }

        val engine = ProtocolEngine.Builder()
            .register(firstReducer)
            .register(secondReducer)
            .build()

        val result = engine.process(ProtocolState(), TestInput("test"))

        assertTrue(firstReducerCalled)
        assertTrue(secondReducerCalled)
        assertEquals(2, result.signals.size)
        assertEquals(TestSignal("first"), result.signals[0])
        assertEquals(TestSignal("second"), result.signals[1])
    }

    @Test
    fun consumedFlagStopsFurtherProcessing() {
        var firstReducerCalled = false
        var secondReducerCalled = false

        val consumingReducer = object : ProtocolReducer {
            override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
                firstReducerCalled = true
                return ReduceResult(
                    state = state,
                    signals = listOf(TestSignal("consumed")),
                    consumed = true,
                )
            }
        }

        val skippedReducer = object : ProtocolReducer {
            override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
                secondReducerCalled = true
                return ReduceResult(state = state, signals = listOf(TestSignal("skipped")))
            }
        }

        val engine = ProtocolEngine.Builder()
            .register(consumingReducer)
            .register(skippedReducer)
            .build()

        val result = engine.process(ProtocolState(), TestInput("test"))

        assertTrue(firstReducerCalled)
        // Second reducer should NOT be called due to consumed flag
        assertTrue(!secondReducerCalled)
        assertEquals(1, result.signals.size)
        assertEquals(TestSignal("consumed"), result.signals[0])
    }

    @Test
    fun effectsAreCollectedFromAllReducers() {
        // Create a dummy client message view for the effect
        val dummyRaw = io.github.nicolals.nostr.core.message.raw.RawClientMessage(
            io.github.nicolals.nostr.core.codec.JsonArray(listOf(io.github.nicolals.nostr.core.codec.JsonString("TEST")))
        )
        val dummyMessage = object : io.github.nicolals.nostr.core.message.view.ClientMessageView {
            override val raw = dummyRaw
        }

        val effectProducingReducer1 = object : ProtocolReducer {
            override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
                return ReduceResult(
                    state = state,
                    effects = listOf(ProtocolEffect.Send(dummyMessage)),
                )
            }
        }

        val effectProducingReducer2 = object : ProtocolReducer {
            override fun reduce(state: ProtocolState, input: ProtocolInput): ReduceResult? {
                return ReduceResult(
                    state = state,
                    effects = listOf(ProtocolEffect.ScheduleTimer(TimerKey("test"), 1000)),
                )
            }
        }

        val engine = ProtocolEngine.Builder()
            .register(effectProducingReducer1)
            .register(effectProducingReducer2)
            .build()

        val result = engine.process(ProtocolState(), TestInput("test"))

        assertEquals(2, result.effects.size)
        assertTrue(result.effects[0] is ProtocolEffect.Send)
        assertTrue(result.effects[1] is ProtocolEffect.ScheduleTimer)
    }
}
