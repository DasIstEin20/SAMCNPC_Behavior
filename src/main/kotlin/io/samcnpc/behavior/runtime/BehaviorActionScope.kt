package io.samcnpc.behavior.runtime

import io.samcnpc.behavior.model.ActionIntent
import io.samcnpc.core.api.*
import java.util.EnumMap
import java.util.UUID

/** One selected producer's transient generation; neither Core IDs nor controls are persisted. */
internal class BehaviorActionScope {
    internal enum class Mechanism { LOCOMOTION, BLOCK_BREAK, RANGED, ITEM_USE }

    internal class Execution(val intent: ActionIntent) {
        val generation: UUID = UUID.randomUUID()
        val active = EnumMap<Mechanism, UUID>(Mechanism::class.java)
        val completed = EnumMap<Mechanism, NpcActionResult>(Mechanism::class.java)
    }

    private val executions = linkedMapOf<ActionIntent, Execution>()

    /** Release losers before executing any winner, including when no intent was selected. */
    fun prepare(selected: List<ActionIntent>, npc: NpcFacade, released: (ActionIntent) -> Unit = {}) {
        val obsolete = executions.keys.filter { it !in selected }
        for (intent in obsolete) release(intent, npc, released)
        for (intent in selected) executions.getOrPut(intent) { Execution(intent) }
    }

    fun clear(npc: NpcFacade, released: (ActionIntent) -> Unit = {}) {
        for (intent in executions.keys.toList()) release(intent, npc, released)
    }

    fun releaseMatching(npc: NpcFacade, matches: (ActionIntent) -> Boolean, released: (ActionIntent) -> Unit = {}) {
        for (intent in executions.keys.filter(matches)) release(intent, npc, released)
    }

    fun forget() { executions.clear() }

    fun execution(intent: ActionIntent): Execution? = executions[intent]

    fun facade(intent: ActionIntent, npc: NpcFacade): NpcFacade {
        val execution = checkNotNull(executions[intent]) { "intent must win arbitration before execution" }
        return ScopedNpcFacade(npc, this, execution)
    }

    fun completed(result: NpcActionResult) {
        val actionId = result.actionId ?: return
        if (result.status != NpcActionStatus.SUCCEEDED && result.status != NpcActionStatus.FAILED) return
        for (execution in executions.values) {
            val mechanism = execution.active.entries.firstOrNull { it.value == actionId }?.key ?: continue
            execution.active.remove(mechanism)
            execution.completed[mechanism] = result
            return
        }
    }

    fun takeCompletion(intent: ActionIntent, mechanism: Mechanism): NpcActionResult? =
        executions[intent]?.completed?.remove(mechanism)

    private fun release(intent: ActionIntent, npc: NpcFacade, released: (ActionIntent) -> Unit) {
        // Both mappings disappear before abort/cancel can synchronously publish a terminal event.
        val execution = executions.remove(intent) ?: return
        released(intent)
        val snapshot = npc.snapshot()
        for ((mechanism, actionId) in execution.active) {
            when (mechanism) {
                Mechanism.LOCOMOTION -> if (snapshot.control?.actionId == actionId || snapshot.navigation?.actionId == actionId) npc.stopControl()
                Mechanism.BLOCK_BREAK -> if (snapshot.blockBreak?.actionId == actionId) npc.abortBlockBreak()
                Mechanism.RANGED -> if (snapshot.rangedAttack?.actionId == actionId) npc.cancelRangedAttack()
                Mechanism.ITEM_USE -> if (snapshot.itemUse?.actionId == actionId) npc.cancelItemUse()
            }
        }
        execution.active.clear()
        execution.completed.clear()
    }

    private fun invoke(execution: Execution, mechanism: Mechanism, action: () -> NpcActionResult): NpcActionResult {
        if (executions[execution.intent] !== execution) {
            return NpcActionResult.rejected("behavior execution was superseded", NpcActionCode.CANCELLED)
        }
        val result = action()
        if (executions[execution.intent] !== execution) return result
        val id = result.actionId
        if (id != null && (result.status == NpcActionStatus.ACCEPTED || result.status == NpcActionStatus.RUNNING)) {
            execution.active[mechanism] = id
            execution.completed.remove(mechanism)
        }
        return result
    }

    private class ScopedNpcFacade(
        private val delegate: NpcFacade,
        private val scope: BehaviorActionScope,
        private val execution: Execution,
    ) : NpcFacade by delegate {
        override fun applyControl(input: NpcControlInput) = scope.invoke(execution, Mechanism.LOCOMOTION) { delegate.applyControl(input) }
        override fun navigateTo(position: NpcPosition, speedMultiplier: Float) = scope.invoke(execution, Mechanism.LOCOMOTION) { delegate.navigateTo(position, speedMultiplier) }
        override fun navigateTo(request: NpcNavigationRequest) = scope.invoke(execution, Mechanism.LOCOMOTION) { delegate.navigateTo(request) }
        override fun startBlockBreak(position: NpcBlockPosition) = scope.invoke(execution, Mechanism.BLOCK_BREAK) { delegate.startBlockBreak(position) }
        override fun continueBlockBreak() = scope.invoke(execution, Mechanism.BLOCK_BREAK) { delegate.continueBlockBreak() }
        override fun startRangedAttack(entityUuid: UUID, hand: NpcHand) = scope.invoke(execution, Mechanism.RANGED) { delegate.startRangedAttack(entityUuid, hand) }
        override fun startItemUse(hand: NpcHand) = scope.invoke(execution, Mechanism.ITEM_USE) { delegate.startItemUse(hand) }
        override fun continueItemUse() = scope.invoke(execution, Mechanism.ITEM_USE) { delegate.continueItemUse() }
        override fun useItemInAir(hand: NpcHand) = scope.invoke(execution, Mechanism.ITEM_USE) { delegate.useItemInAir(hand) }
        override fun useItemOnBlock(hit: NpcBlockHit, hand: NpcHand) = scope.invoke(execution, Mechanism.ITEM_USE) { delegate.useItemOnBlock(hit, hand) }
    }
}
