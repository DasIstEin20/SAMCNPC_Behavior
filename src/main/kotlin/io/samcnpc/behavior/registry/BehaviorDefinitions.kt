package io.samcnpc.behavior.registry

import com.google.gson.JsonObject
import io.samcnpc.behavior.model.ActionHandler
import io.samcnpc.behavior.model.BehaviorChannel
import io.samcnpc.behavior.model.BehaviorReadContext
import io.samcnpc.behavior.model.ConditionHandler
import io.samcnpc.behavior.runtime.BehaviorTargetMemory
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcControlInput
import io.samcnpc.core.api.NpcEntityObservation
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcSnapshot
import io.samcnpc.core.api.NpcWorldView

/** Allow-listed decisions compile data once; only captured facts reach condition handlers. */
object BehaviorDefinitions {
    val conditions: Map<String, ConditionDefinition> = java.util.Map.copyOf(listOf(
        readCondition("samcnpc:always") { true },
        readCondition("samcnpc:task_ready") { it.taskReady },
        readCondition("samcnpc:task_combat_ready") { it.taskCombatReady },
        readCondition("samcnpc:task_reaction_ready") { it.taskReactionReady },
        readCondition("samcnpc:task_inventory_ready") { it.taskInventoryReady },
        readCondition("samcnpc:task_inventory_requested") { it.taskInventoryRequested },
        readCondition("samcnpc:has_summoner") { it.snapshot.summonerUuid != null },
        readCondition("samcnpc:summoner_online") { it.summoner?.alive == true && it.summoner.isPlayer },
        ConditionDefinition("samcnpc:distance_to_summoner", ::distanceArgs) { args ->
            val operator = Comparison.parse(args.get("operator").asString)
            val blocks = args.get("blocks").asDouble
            ConditionHandler { context ->
                val summoner = context.summoner
                summoner != null && operator.test(horizontalDistance(context.snapshot, summoner), blocks)
            }
        },
        readCondition("samcnpc:has_unhandled_damage") { it.unhandledDamage },
        readCondition("samcnpc:has_attack_target") { it.attackTarget?.alive == true },
        ConditionDefinition("samcnpc:was_hurt_recently", ::hurtAgeArgs) { args ->
            val withinTicks = args.get("withinTicks").asLong
            ConditionHandler { context ->
                val age = context.snapshot.lastDamageAgeTicks
                age != null && age in 0..withinTicks
            }
        },
        readCondition("samcnpc:target_alive") { it.attackTarget?.alive == true },
        ConditionDefinition("samcnpc:health_fraction", ::healthFractionArgs) { args ->
            val operator = Comparison.parse(args.get("operator").asString)
            val value = args.get("value").asDouble
            ConditionHandler { context -> operator.test(context.snapshot.healthFraction, value) }
        },
    ).associateBy { it.id })

    val actions: Map<String, ActionDefinition> = java.util.Map.copyOf(listOf(
        action("samcnpc:look_at_summoner", setOf(BehaviorChannel.LOOK)) { npc, _, context ->
            val summoner = context.summoner
                ?: return@action NpcActionResult.rejected("summoner is unavailable in the current bounded world view")
            npc.lookAtEntity(summoner.uuid)
        },
        action("samcnpc:look_at_target", setOf(BehaviorChannel.LOOK)) { npc, _, context ->
            val target = context.attackTarget ?: return@action NpcActionResult.rejected("behavior target is unavailable")
            npc.lookAtEntity(target.uuid)
        },
        io.samcnpc.behavior.runtime.FollowMovement.definition(),
        moveAction("samcnpc:move_to_target") { it.attackTarget },
        action("samcnpc:stop_movement", setOf(BehaviorChannel.MOVEMENT)) { npc, _, _ -> npc.stopControl() },
        action("samcnpc:attack_target", setOf(BehaviorChannel.COMBAT, BehaviorChannel.MAIN_HAND)) { npc, _, context ->
            val target = context.attackTarget ?: return@action NpcActionResult.rejected("behavior target is unavailable")
            if (context.snapshot.attackStrength < 0.9F) return@action NpcActionResult.running("waiting for the real melee cooldown")
            npc.attackEntity(target.uuid)
        },
        BehaviorTargetMemory.definition(),
        action("samcnpc:clear_attack_target", setOf(BehaviorChannel.COMBAT)) { _, _, context ->
            BehaviorTargetMemory.clear(context.snapshot.npcUuid)
        },
        action(io.samcnpc.behavior.task.TaskService.INVENTORY_BEGIN_ACTION_ID, BehaviorChannel.entries.toSet()) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull() ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskLogistics.begin(server, npc, world)
        },
        action(io.samcnpc.behavior.task.TaskService.INVENTORY_ACTION_ID, setOf(BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK,
            BehaviorChannel.INVENTORY, BehaviorChannel.INTERACTION)) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull() ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.INVENTORY_ACTION_ID)
        },
        action(io.samcnpc.behavior.task.TaskService.REACTION_ACTION_ID, setOf(BehaviorChannel.COMBAT)) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull() ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskCombatReactions.begin(server, npc, world)
        },
        action(io.samcnpc.behavior.task.TaskService.COMBAT_ACTION_ID, setOf(BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK,
            BehaviorChannel.COMBAT, BehaviorChannel.MAIN_HAND, BehaviorChannel.OFF_HAND, BehaviorChannel.INVENTORY)) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull() ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.COMBAT_ACTION_ID)
        },
        action(io.samcnpc.behavior.task.TaskService.ACTION_ID, setOf(BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK)) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world)
        },
        action(io.samcnpc.behavior.task.TaskService.DELIVERY_ACTION_ID, setOf(
            BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK, BehaviorChannel.INVENTORY, BehaviorChannel.INTERACTION,
        )) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.DELIVERY_ACTION_ID)
        },
        action(io.samcnpc.behavior.task.TaskService.MINING_ACTION_ID, setOf(
            BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK, BehaviorChannel.MAIN_HAND,
            BehaviorChannel.BLOCK_ACTION, BehaviorChannel.INVENTORY, BehaviorChannel.INTERACTION,
        )) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.MINING_ACTION_ID)
        },
        action(io.samcnpc.behavior.task.TaskService.FOOD_ACTION_ID, setOf(
            BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK, BehaviorChannel.MAIN_HAND,
            BehaviorChannel.BLOCK_ACTION, BehaviorChannel.INVENTORY, BehaviorChannel.INTERACTION,
        )) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.FOOD_ACTION_ID)
        },
        action(io.samcnpc.behavior.task.TaskService.EXPLORER_ACTION_ID, setOf(
            BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK,
        )) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.EXPLORER_ACTION_ID)
        },
        action(io.samcnpc.behavior.task.TaskService.FISHING_ACTION_ID, setOf(
            BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK, BehaviorChannel.MAIN_HAND,
            BehaviorChannel.BLOCK_ACTION, BehaviorChannel.INVENTORY, BehaviorChannel.INTERACTION, BehaviorChannel.OFF_HAND, BehaviorChannel.COMBAT,
        )) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.FISHING_ACTION_ID)
        },
        action(io.samcnpc.behavior.task.TaskService.MACHINE_ACTION_ID, setOf(
            BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK, BehaviorChannel.MAIN_HAND,
            BehaviorChannel.BLOCK_ACTION, BehaviorChannel.INVENTORY, BehaviorChannel.INTERACTION,
        )) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.MACHINE_ACTION_ID)
        },
        action(io.samcnpc.behavior.task.TaskService.FIELD_ACTION_ID, setOf(
            BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK, BehaviorChannel.MAIN_HAND,
            BehaviorChannel.BLOCK_ACTION, BehaviorChannel.INVENTORY, BehaviorChannel.INTERACTION,
        )) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.FIELD_ACTION_ID)
        },
        action(io.samcnpc.behavior.task.TaskService.PLANTING_ACTION_ID, setOf(
            BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK, BehaviorChannel.MAIN_HAND,
            BehaviorChannel.BLOCK_ACTION, BehaviorChannel.INVENTORY, BehaviorChannel.INTERACTION,
        )) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.PLANTING_ACTION_ID)
        },
        action(io.samcnpc.behavior.task.TaskService.FARM_ACTION_ID, setOf(
            BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK, BehaviorChannel.MAIN_HAND,
            BehaviorChannel.BLOCK_ACTION, BehaviorChannel.INVENTORY, BehaviorChannel.INTERACTION,
        )) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.FARM_ACTION_ID)
        },
        action(io.samcnpc.behavior.task.TaskService.LUMBERJACK_ACTION_ID, setOf(
            BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK, BehaviorChannel.MAIN_HAND,
            BehaviorChannel.BLOCK_ACTION, BehaviorChannel.INVENTORY, BehaviorChannel.INTERACTION,
        )) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            io.samcnpc.behavior.task.TaskService.executeSelected(server, npc, world, io.samcnpc.behavior.task.TaskService.LUMBERJACK_ACTION_ID)
        },
        action("samcnpc:run_lumberjack_demo", setOf(
            BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK, BehaviorChannel.MAIN_HAND,
            BehaviorChannel.BLOCK_ACTION, BehaviorChannel.INVENTORY, BehaviorChannel.INTERACTION,
        )) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            LumberjackService.tick(server, npc, world)
        },
    ).associateBy { it.id })

    val compiler = BehaviorPackCompiler(conditions, actions)

    private fun readCondition(id: String, handler: (BehaviorReadContext) -> Boolean): ConditionDefinition =
        ConditionDefinition(id, ::noArgs) { ConditionHandler(handler) }

    private fun action(
        id: String,
        channels: Set<BehaviorChannel>,
        handler: (NpcFacade, NpcWorldView, BehaviorReadContext) -> NpcActionResult,
    ): ActionDefinition = ActionDefinition(id, channels, ::noArgs) { ActionHandler(handler) }

    private fun moveAction(id: String, target: (BehaviorReadContext) -> NpcEntityObservation?): ActionDefinition =
        ActionDefinition(id, setOf(BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK), ::moveArgs) { args ->
            val speed = args.get("speed").asFloat
            val stopDistance = args.get("stopDistance").asDouble
            ActionHandler { npc, _, context ->
                val observation = target(context)
                    ?: return@ActionHandler NpcActionResult.rejected("movement target is unavailable in the current bounded world view")
                steerToward(npc, context.snapshot, observation, speed, stopDistance)
            }
        }

    private fun steerToward(
        npc: NpcFacade,
        snapshot: NpcSnapshot,
        target: NpcEntityObservation,
        speed: Float,
        stopDistance: Double,
    ): NpcActionResult {
        if (horizontalDistance(snapshot, target) <= stopDistance) return npc.stopControl()
        val look = npc.lookAtEntity(target.uuid)
        when (look.status) {
            NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED -> return look
            else -> Unit
        }
        // Speed above one requests the normal sprint flag, never a hidden physics boost.
        return npc.applyControl(NpcControlInput(
            forward = 1.0F, strafe = 0.0F, speedMultiplier = speed.coerceAtMost(1.0F), sprint = speed > 1.0F,
        ))
    }

    private fun horizontalDistance(snapshot: NpcSnapshot, entity: NpcEntityObservation): Double {
        val dx = entity.position.x - snapshot.position.x
        val dz = entity.position.z - snapshot.position.z
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    private fun noArgs(args: JsonObject): String? = if (args.size() == 0) null else "does not accept arguments"

    private fun distanceArgs(args: JsonObject): String? {
        val operator = args.string("operator") ?: return "requires string argument 'operator'"
        val blocks = args.finiteNumber("blocks") ?: return "requires finite numeric argument 'blocks'"
        return when {
            operator !in COMPARATORS -> "operator must be one of ${COMPARATORS.sorted()}"
            blocks < 0.0 || blocks > 256.0 -> "blocks must be in [0, 256]"
            args.size() != 2 -> "accepts only operator and blocks"
            else -> null
        }
    }

    private fun hurtAgeArgs(args: JsonObject): String? {
        val ticks = args.long("withinTicks") ?: return "requires integer argument 'withinTicks'"
        return when {
            ticks !in 1..120_000 -> "withinTicks must be in [1, 120000]"
            args.size() != 1 -> "accepts only withinTicks"
            else -> null
        }
    }

    private fun healthFractionArgs(args: JsonObject): String? {
        val operator = args.string("operator") ?: return "requires string argument 'operator'"
        val value = args.finiteNumber("value") ?: return "requires finite numeric argument 'value'"
        return when {
            operator !in COMPARATORS -> "operator must be one of ${COMPARATORS.sorted()}"
            value !in 0.0..1.0 -> "value must be in [0, 1]"
            args.size() != 2 -> "accepts only operator and value"
            else -> null
        }
    }

    private fun moveArgs(args: JsonObject): String? {
        val speed = args.finiteNumber("speed") ?: return "requires finite numeric argument 'speed'"
        val stopDistance = args.finiteNumber("stopDistance") ?: return "requires finite numeric argument 'stopDistance'"
        return when {
            speed !in 0.1..1.5 -> "speed must be in [0.1, 1.5]; values above 1 request sprint"
            stopDistance !in 0.0..16.0 -> "stopDistance must be in [0, 16]"
            args.size() != 2 -> "accepts only speed and stopDistance"
            else -> null
        }
    }

    private enum class Comparison(val token: String) {
        GT("gt"), GTE("gte"), LT("lt"), LTE("lte"), EQ("eq");

        fun test(actual: Double, expected: Double): Boolean = when (this) {
            GT -> actual > expected
            GTE -> actual >= expected
            LT -> actual < expected
            LTE -> actual <= expected
            EQ -> actual == expected
        }

        companion object {
            fun parse(token: String): Comparison = entries.first { it.token == token }
        }
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.finiteNumber(name: String): Double? {
        val value = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: return null
        return value.takeIf { it.isFinite() }
    }

    private fun JsonObject.long(name: String): Long? = get(name).exactLongOrNull()

    private val COMPARATORS = setOf("gt", "gte", "lt", "lte", "eq")
}
