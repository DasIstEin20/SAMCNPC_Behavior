package io.samcnpc.behavior.model

import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcEntityObservation
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcSnapshot
import io.samcnpc.core.api.NpcWorldView

enum class BehaviorChannel {
    MOVEMENT, LOOK, MAIN_HAND, OFF_HAND, COMBAT, INTERACTION, BLOCK_ACTION, INVENTORY;

    companion object {
        fun parse(value: String): BehaviorChannel? = entries.firstOrNull { it.name.lowercase() == value }
    }
}

/** A decision reads already captured facts. It cannot call actuators or query mutable world state. */
data class BehaviorReadContext(
    val snapshot: NpcSnapshot,
    val summoner: NpcEntityObservation?,
    val attackTarget: NpcEntityObservation?,
    val taskReady: Boolean = false,
    val unhandledDamage: Boolean = false,
    val taskCombatReady: Boolean = false,
    val taskReactionReady: Boolean = false,
    val taskInventoryReady: Boolean = false,
    val taskInventoryRequested: Boolean = false,
    val inventoryFacts: io.samcnpc.behavior.inventory.InventoryFacts? = null,
    val localTaskFacts: io.samcnpc.behavior.inventory.LocalTaskFacts? = null,
    val containerFacts: Map<io.samcnpc.behavior.inventory.ContainerFactRequest, io.samcnpc.core.api.NpcBlockContainerObservation> = emptyMap(),
)

sealed interface ConditionExpression {
    fun evaluate(context: BehaviorReadContext): Boolean

    class Test(val conditionId: String, val handler: ConditionHandler) : ConditionExpression {
        override fun evaluate(context: BehaviorReadContext): Boolean = handler.evaluate(context)
    }

    class All(children: List<ConditionExpression>) : ConditionExpression {
        val children: List<ConditionExpression> = java.util.List.copyOf(children)
        override fun evaluate(context: BehaviorReadContext): Boolean = children.all { it.evaluate(context) }
    }

    class Any(children: List<ConditionExpression>) : ConditionExpression {
        val children: List<ConditionExpression> = java.util.List.copyOf(children)
        override fun evaluate(context: BehaviorReadContext): Boolean = children.any { it.evaluate(context) }
    }

    class Not(val child: ConditionExpression) : ConditionExpression {
        override fun evaluate(context: BehaviorReadContext): Boolean = !child.evaluate(context)
    }
}

class CompiledAction(
    val actionId: String,
    val handler: ActionHandler,
    channels: Set<BehaviorChannel>,
) {
    val channels: Set<BehaviorChannel> = java.util.Set.copyOf(channels)
    val channelMask: Int = channels.fold(0) { mask, channel -> mask or (1 shl channel.ordinal) }
}

class CompiledRule(
    val id: String,
    val priority: Int,
    val cooldownTicks: Int,
    val whenExpression: ConditionExpression,
    actions: List<CompiledAction>,
) {
    val actions: List<CompiledAction> = java.util.List.copyOf(actions)
}

class CompiledPack(
    val id: String,
    val description: String,
    val priority: Int,
    allowedChannels: Set<BehaviorChannel>,
    rules: List<CompiledRule>,
) {
    val allowedChannels: Set<BehaviorChannel> = java.util.Set.copyOf(allowedChannels)
    val rules: List<CompiledRule> = java.util.List.copyOf(rules)
}

data class ActionIntent(
    val packId: String,
    val packPriority: Int,
    val ruleId: String,
    val rulePriority: Int,
    val actionIndex: Int,
    val action: CompiledAction,
    val cooldownTicks: Int = 0,
) {
    val channels: Set<BehaviorChannel> get() = action.channels
    val cooldownKey: String = "$packId/$ruleId"
    val diagnosticPrefix: String = "$packId/$ruleId/${action.actionId}"
    private val resultLabels: List<String> = NpcActionStatus.entries.map { "$diagnosticPrefix:$it" }

    fun resultLabel(status: NpcActionStatus): String = resultLabels[status.ordinal]

    companion object {
        val WINNER_FIRST: Comparator<ActionIntent> = compareByDescending<ActionIntent> { it.rulePriority }
            .thenByDescending { it.packPriority }
            .thenBy { it.packId }
            .thenBy { it.ruleId }
            .thenBy { it.actionIndex }
    }
}

fun interface ConditionHandler {
    fun evaluate(context: BehaviorReadContext): Boolean
}

fun interface ActionHandler {
    fun execute(npc: NpcFacade, world: NpcWorldView, context: BehaviorReadContext): NpcActionResult
}
