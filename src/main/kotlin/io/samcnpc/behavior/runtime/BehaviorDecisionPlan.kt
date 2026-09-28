package io.samcnpc.behavior.runtime

import io.samcnpc.behavior.model.ActionIntent
import io.samcnpc.behavior.model.BehaviorArbiter
import io.samcnpc.behavior.model.BehaviorReadContext
import io.samcnpc.behavior.model.CompiledPack
import io.samcnpc.behavior.model.CompiledRule
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus

data class BehaviorActionOutcome(val intent: ActionIntent, val result: NpcActionResult)

data class BehaviorDecisionResult(
    val outcomes: List<BehaviorActionOutcome>,
    val evaluatedRules: Int,
    val matchedRules: Int,
    val eligibleIntents: Int,
) {
    val selectedLabels: List<String> get() = outcomes.map { it.intent.resultLabel(it.result.status) }
    val lastProblem: String?
        get() {
            val failed = outcomes.lastOrNull { it.result.status == NpcActionStatus.REJECTED ||
                it.result.status == NpcActionStatus.FAILED || it.result.status == NpcActionStatus.UNSUPPORTED } ?: return null
            return "${failed.intent.cooldownKey}: ${failed.result.detail}"
        }
}

/** Immutable assignment plan. Only facts and bounded cooldown state vary between ticks. */
class BehaviorDecisionPlan(packs: List<CompiledPack>) {
    private data class PreparedRule(val key: String, val rule: CompiledRule)
    private data class Candidate(val ruleIndex: Int, val intent: ActionIntent)
    private val rules: List<PreparedRule>
    private val candidates: List<Candidate>
    val needsInventoryFacts: Boolean
    val needsTaskFacts: Boolean
    val containerRequests: List<io.samcnpc.behavior.inventory.ContainerFactRequest>

    init {
        val prepared = ArrayList<PreparedRule>()
        val actions = ArrayList<Candidate>()
        for (pack in packs.sortedBy { it.id }) {
            for (rule in pack.rules) {
                val index = prepared.size
                prepared.add(PreparedRule("${pack.id}/${rule.id}", rule))
                for ((actionIndex, action) in rule.actions.withIndex()) {
                    actions.add(Candidate(index, ActionIntent(
                        pack.id, pack.priority, rule.id, rule.priority, actionIndex, action, rule.cooldownTicks,
                    )))
                }
            }
        }
        rules = java.util.List.copyOf(prepared)
        candidates = java.util.List.copyOf(actions.sortedWith { left, right ->
            ActionIntent.WINNER_FIRST.compare(left.intent, right.intent)
        })
        val requests = ArrayList<io.samcnpc.behavior.inventory.LocalFactCondition>()
        for (preparedRule in prepared) collectFacts(preparedRule.rule.whenExpression, requests)
        needsInventoryFacts = requests.any { it.needsInventory }
        needsTaskFacts = requests.any { it.needsTask }
        containerRequests = java.util.List.copyOf(requests.mapNotNull { it.container }.distinct())
    }

    private fun collectFacts(expression: io.samcnpc.behavior.model.ConditionExpression,
                             result: MutableList<io.samcnpc.behavior.inventory.LocalFactCondition>) {
        when (expression) {
            is io.samcnpc.behavior.model.ConditionExpression.Test -> (expression.handler as? io.samcnpc.behavior.inventory.LocalFactCondition)?.let(result::add)
            is io.samcnpc.behavior.model.ConditionExpression.All -> expression.children.forEach { collectFacts(it, result) }
            is io.samcnpc.behavior.model.ConditionExpression.Any -> expression.children.forEach { collectFacts(it, result) }
            is io.samcnpc.behavior.model.ConditionExpression.Not -> collectFacts(expression.child, result)
        }
    }

    fun tick(
        context: BehaviorReadContext,
        cooldownUntil: MutableMap<String, Long>,
        beforeExecution: (List<ActionIntent>) -> Unit = {},
        execute: (ActionIntent) -> NpcActionResult,
    ): BehaviorDecisionResult {
        val gameTime = context.snapshot.gameTime
        val enabled = BooleanArray(rules.size)
        var evaluatedRules = 0
        var matchedRules = 0
        for ((index, prepared) in rules.withIndex()) {
            if ((cooldownUntil[prepared.key] ?: Long.MIN_VALUE) <= gameTime) {
                evaluatedRules++
                enabled[index] = prepared.rule.whenExpression.evaluate(context)
                if (enabled[index]) matchedRules++
            }
        }
        val eligible = ArrayList<ActionIntent>()
        for (candidate in candidates) {
            if (enabled[candidate.ruleIndex]) eligible.add(candidate.intent)
        }
        val selected = BehaviorArbiter.chooseOrdered(eligible)
        beforeExecution(selected)
        val outcomes = ArrayList<BehaviorActionOutcome>(selected.size)
        for (intent in selected) {
            val result = execute(intent)
            outcomes.add(BehaviorActionOutcome(intent, result))
            when (result.status) {
                NpcActionStatus.ACCEPTED, NpcActionStatus.SUCCEEDED -> {
                    if (intent.cooldownTicks > 0) {
                        val duration = intent.cooldownTicks.toLong()
                        cooldownUntil[intent.cooldownKey] =
                            if (gameTime > Long.MAX_VALUE - duration) Long.MAX_VALUE else gameTime + duration
                    }
                }
                NpcActionStatus.RUNNING -> Unit
                NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED -> Unit
            }
        }
        return BehaviorDecisionResult(java.util.List.copyOf(outcomes), evaluatedRules, matchedRules, eligible.size)
    }
}
