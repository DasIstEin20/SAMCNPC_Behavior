package io.samcnpc.behavior.registry

import com.google.gson.JsonObject
import io.samcnpc.behavior.model.ActionDefinition
import io.samcnpc.behavior.model.ActionHandler
import io.samcnpc.behavior.model.BehaviorChannel
import io.samcnpc.behavior.model.ConditionDefinition
import io.samcnpc.behavior.model.ConditionHandler
import io.samcnpc.behavior.runtime.BehaviorTargetMemory
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcControlInput
import io.samcnpc.core.api.NpcEntityObservation
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcSnapshot
import io.samcnpc.core.api.NpcWorldView

/** Allow-listed v1 behavior decisions. Core receives only the resulting explicit capabilities. */
object BehaviorDefinitions {
    val conditions: Map<String, ConditionDefinition> = listOf(
        condition("samcnpc:always", ::noArgs) { _, _, _, _ -> true },
        condition("samcnpc:has_summoner", ::noArgs) { _, _, snapshot, _ -> snapshot.summonerUuid != null },
        condition("samcnpc:summoner_online", ::noArgs) { _, world, snapshot, _ ->
            snapshot.summonerUuid?.let(world::observeEntity)?.let { it.alive && it.isPlayer } == true
        },
        condition("samcnpc:distance_to_summoner", ::distanceArgs) { _, world, snapshot, args ->
            val summoner = snapshot.summonerUuid?.let(world::observeEntity) ?: return@condition false
            val distance = horizontalDistance(snapshot, summoner)
            compare(distance, args.get("operator").asString, args.get("blocks").asDouble)
        },
        condition("samcnpc:has_attack_target", ::noArgs) { npc, world, _, _ -> BehaviorTargetMemory.hasLiveTarget(npc, world) },
        condition("samcnpc:was_hurt_recently", ::hurtAgeArgs) { _, _, snapshot, args ->
            val age = snapshot.lastDamageAgeTicks ?: return@condition false
            age <= args.get("withinTicks").asLong
        },
        condition("samcnpc:target_alive", ::noArgs) { npc, world, _, _ -> BehaviorTargetMemory.hasLiveTarget(npc, world) },
        condition("samcnpc:health_fraction", ::healthFractionArgs) { _, _, snapshot, args ->
            compare(snapshot.healthFraction, args.get("operator").asString, args.get("value").asDouble)
        },
    ).associateBy { it.id }

    val actions: Map<String, ActionDefinition> = listOf(
        action("samcnpc:look_at_summoner", setOf(BehaviorChannel.LOOK), ::noArgs) { npc, world, _ ->
            val summoner = resolveSummoner(npc, world) ?: return@action NpcActionResult.rejected("summoner is unavailable in the current bounded world view")
            npc.lookAtEntity(summoner.uuid)
        },
        action("samcnpc:look_at_target", setOf(BehaviorChannel.LOOK), ::noArgs) { npc, world, _ ->
            val target = BehaviorTargetMemory.resolveLiveTarget(npc, world)
                ?: return@action NpcActionResult.rejected("behavior target is unavailable")
            npc.lookAtEntity(target.uuid)
        },
        action("samcnpc:move_to_summoner", setOf(BehaviorChannel.MOVEMENT), ::moveArgs) { npc, world, args ->
            val summoner = resolveSummoner(npc, world) ?: return@action NpcActionResult.rejected("summoner is unavailable in the current bounded world view")
            steerToward(npc, summoner, args.get("speed").asFloat, args.get("stopDistance").asDouble)
        },
        action("samcnpc:move_to_target", setOf(BehaviorChannel.MOVEMENT), ::moveArgs) { npc, world, args ->
            val target = BehaviorTargetMemory.resolveLiveTarget(npc, world)
                ?: return@action NpcActionResult.rejected("behavior target is unavailable")
            steerToward(npc, target, args.get("speed").asFloat, args.get("stopDistance").asDouble)
        },
        action("samcnpc:stop_movement", setOf(BehaviorChannel.MOVEMENT), ::noArgs) { npc, _, _ -> npc.stopControl() },
        action("samcnpc:attack_target", setOf(BehaviorChannel.COMBAT, BehaviorChannel.MAIN_HAND), ::noArgs) { npc, world, _ ->
            val target = BehaviorTargetMemory.resolveLiveTarget(npc, world)
                ?: return@action NpcActionResult.rejected("behavior target is unavailable")
            npc.attackEntity(target.uuid)
        },
        action("samcnpc:set_attack_target_from_recent_attacker", setOf(BehaviorChannel.COMBAT), ::noArgs) { npc, _, _ ->
            BehaviorTargetMemory.acquireFromRecentDamage(npc)
        },
        action("samcnpc:clear_attack_target", setOf(BehaviorChannel.COMBAT), ::noArgs) { npc, _, _ ->
            BehaviorTargetMemory.clear(npc.npcUuid)
        },
        action(
            "samcnpc:run_lumberjack_demo",
            setOf(
                BehaviorChannel.MOVEMENT,
                BehaviorChannel.LOOK,
                BehaviorChannel.MAIN_HAND,
                BehaviorChannel.BLOCK_ACTION,
                BehaviorChannel.INVENTORY,
            ),
            ::noArgs,
        ) { npc, world, _ ->
            val server = BehaviorRuntimeService.serverOrNull()
                ?: return@action NpcActionResult.rejected("behavior server is not ready")
            LumberjackService.tick(server, npc, world)
        },
    ).associateBy { it.id }

    val compiler = BehaviorPackCompiler(conditions, actions)

    private fun condition(
        id: String,
        validate: (JsonObject) -> String?,
        handler: (NpcFacade, NpcWorldView, NpcSnapshot, JsonObject) -> Boolean,
    ): ConditionDefinition = ConditionDefinition(id, validate, ConditionHandler(handler))

    private fun action(
        id: String,
        channels: Set<BehaviorChannel>,
        validate: (JsonObject) -> String?,
        handler: (NpcFacade, NpcWorldView, JsonObject) -> NpcActionResult,
    ): ActionDefinition = ActionDefinition(id, channels, validate, ActionHandler(handler))

    private fun resolveSummoner(npc: NpcFacade, world: NpcWorldView): NpcEntityObservation? {
        val snapshot = npc.snapshot()
        return snapshot.summonerUuid?.let(world::observeEntity)?.takeIf { it.alive && it.isPlayer }
    }

    private fun steerToward(npc: NpcFacade, target: NpcEntityObservation, speed: Float, stopDistance: Double): NpcActionResult {
        val snapshot = npc.snapshot()
        if (horizontalDistance(snapshot, target) <= stopDistance) {
            return npc.stopControl()
        }
        val lookResult = npc.lookAtEntity(target.uuid)
        if (lookResult.status == io.samcnpc.core.api.NpcActionStatus.REJECTED) {
            return lookResult
        }
        // Pack speed above one is a bounded request to use the normal sprint flag, not a Core
        // speed boost. Core still receives a legal analog input in [0, 1].
        return npc.applyControl(
            NpcControlInput(
                forward = 1.0F,
                strafe = 0.0F,
                speedMultiplier = speed.coerceAtMost(1.0F),
                sprint = speed > 1.0F,
            ),
        )
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

    private fun compare(actual: Double, operator: String, expected: Double): Boolean = when (operator) {
        "gt" -> actual > expected
        "gte" -> actual >= expected
        "lt" -> actual < expected
        "lte" -> actual <= expected
        "eq" -> actual == expected
        else -> false
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.finiteNumber(name: String): Double? {
        val value = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: return null
        return value.takeIf { it.isFinite() }
    }

    private fun JsonObject.long(name: String): Long? {
        val primitive = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asJsonPrimitive ?: return null
        val number = primitive.asNumber
        val long = number.toLong()
        return long.takeIf { number.toDouble() == it.toDouble() }
    }

    private val COMPARATORS = setOf("gt", "gte", "lt", "lte", "eq")
}
