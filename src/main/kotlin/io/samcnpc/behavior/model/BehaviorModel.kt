package io.samcnpc.behavior.model

import com.google.gson.JsonObject
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcSnapshot
import io.samcnpc.core.api.NpcWorldView

enum class BehaviorChannel {
    MOVEMENT,
    LOOK,
    MAIN_HAND,
    OFF_HAND,
    COMBAT,
    INTERACTION,
    BLOCK_ACTION,
    INVENTORY;

    companion object {
        fun parse(value: String): BehaviorChannel? = entries.firstOrNull { it.name.lowercase() == value }
    }
}

sealed interface ConditionExpression {
    data class Test(val conditionId: String, val args: JsonObject) : ConditionExpression
    data class All(val children: List<ConditionExpression>) : ConditionExpression
    data class Any(val children: List<ConditionExpression>) : ConditionExpression
    data class Not(val child: ConditionExpression) : ConditionExpression
}

data class CompiledAction(
    val actionId: String,
    val args: JsonObject,
    val channels: Set<BehaviorChannel>,
)

data class CompiledRule(
    val id: String,
    val priority: Int,
    val cooldownTicks: Int,
    val whenExpression: ConditionExpression,
    val actions: List<CompiledAction>,
)

data class CompiledPack(
    val id: String,
    val description: String,
    val priority: Int,
    val allowedChannels: Set<BehaviorChannel>,
    val rules: List<CompiledRule>,
)

data class ActionIntent(
    val packId: String,
    val packPriority: Int,
    val ruleId: String,
    val rulePriority: Int,
    val actionIndex: Int,
    val action: CompiledAction,
) {
    val channels: Set<BehaviorChannel>
        get() = action.channels

    /** Explicit stable arbitration order; larger priorities win, names/index break ties. */
    companion object {
        val WINNER_FIRST: Comparator<ActionIntent> = compareByDescending<ActionIntent> { it.rulePriority }
            .thenByDescending { it.packPriority }
            .thenBy { it.packId }
            .thenBy { it.ruleId }
            .thenBy { it.actionIndex }
    }
}

fun interface ConditionHandler {
    fun evaluate(npc: NpcFacade, world: NpcWorldView, snapshot: NpcSnapshot, args: JsonObject): Boolean
}

fun interface ActionHandler {
    fun execute(npc: NpcFacade, world: NpcWorldView, args: JsonObject): NpcActionResult
}

data class ConditionDefinition(
    val id: String,
    val validateArgs: (JsonObject) -> String?,
    val handler: ConditionHandler,
)

data class ActionDefinition(
    val id: String,
    val channels: Set<BehaviorChannel>,
    val validateArgs: (JsonObject) -> String?,
    val handler: ActionHandler,
)
