package io.github.nicolals.nostr.core.protocol

/** Application commands accepted by a runtime, excluding synthetic inbound/lifecycle events. */
interface ProtocolIntent : ProtocolInput

/** An operation that must pass admission before any reducer can produce effects. */
interface OperationIntent : ProtocolIntent

/** Pure, session-local admission policy. Store evolving decisions in ProtocolState. */
fun interface OperationAdmission {
    fun evaluate(state: ProtocolState, intent: OperationIntent): AdmissionDecision
}

sealed interface AdmissionDecision {
    data object Allow : AdmissionDecision
    /** Terminal refusal; no reducer has run and no effects have been produced. */
    data class Reject(val reason: String) : AdmissionDecision
    /** Wait for protocol state to change before trying this operation again. */
    data class Defer(val reason: String) : AdmissionDecision
}

/** Engine outcome, including a decision made before reducer execution. */
data class ProtocolResult(
    val state: ProtocolState,
    val effects: List<ProtocolEffect> = emptyList(),
    val signals: List<ProtocolSignal> = emptyList(),
    val admission: AdmissionDecision = AdmissionDecision.Allow,
)
