package io.samcnpc.behavior.inventory

import io.samcnpc.behavior.model.BehaviorReadContext
import io.samcnpc.behavior.model.ConditionHandler

/** Declared capture requirements are inspected once when an immutable decision plan is built. */
internal class LocalFactCondition(
    val needsInventory: Boolean = false,
    val needsTask: Boolean = false,
    val container: ContainerFactRequest? = null,
    private val evaluateFacts: (BehaviorReadContext) -> Boolean,
) : ConditionHandler {
    override fun evaluate(context: BehaviorReadContext) = evaluateFacts(context)
}

data class LocalTaskFacts(val status: String, val attemptsRemaining: Int, val lastFailure: String?)

enum class ContainerFactEndpoint { SOURCE, DESTINATION }
data class ContainerFactRequest(val endpoint: ContainerFactEndpoint, val index: Int) {
    init { require(index in 0..7) }
}
