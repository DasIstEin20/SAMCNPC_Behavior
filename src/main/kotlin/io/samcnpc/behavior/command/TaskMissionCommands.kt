package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.UuidArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument

/** Typed commands only; quoted filters/routes are bounded values, never executable documents. */
internal object TaskMissionCommands {
    fun defendBranch() = Commands.literal("defend")
        .then(Commands.literal("summoner").then(radius().then(duty { context, players -> defend(context, players, "summoner") })))
        .then(Commands.literal("npc").then(Commands.argument("subject", UuidArgument.uuid())
            .then(radius().then(duty { context, players -> defend(context, players, "npc") }))))
        .then(Commands.literal("point").then(Commands.argument("center", Vec3Argument.vec3())
            .then(filter().then(duty { context, players -> defend(context, players, "point") }))))
        .then(Commands.literal("area").then(Commands.argument("center", Vec3Argument.vec3())
            .then(radius().then(filter().then(duty { context, players -> defend(context, players, "area") })))))

    fun areaBranch() = Commands.literal("attack_area").then(Commands.argument("center", Vec3Argument.vec3())
        .then(radius().then(filter().then(Commands.argument("quota", IntegerArgumentType.integer(1, 64))
            .then(duration().executes { area(it, false) }.then(players { context, allow -> area(context, allow) }))))))

    fun patrolBranch() = Commands.literal("patrol").then(radius()
        .then(Commands.argument("rounds", IntegerArgumentType.integer(1, 64))
            .then(Commands.argument("dwellTicks", IntegerArgumentType.integer(0, 1200))
                .then(duration().then(Commands.argument("route", StringArgumentType.string())
                    .executes { patrol(it, PatrolReaction.RETALIATE, false) }
                    .then(Commands.literal("passive").executes { patrol(it, PatrolReaction.PASSIVE, false) })
                    .then(patrolReaction("retaliate", PatrolReaction.RETALIATE))
                    .then(patrolReaction("protect_summoner", PatrolReaction.PROTECT_SUMMONER))
                    .then(Commands.literal("support").then(Commands.argument("subject", UuidArgument.uuid())
                        .executes { patrol(it, PatrolReaction.SUPPORT, false) }
                        .then(players { context, allow -> patrol(context, PatrolReaction.SUPPORT, allow) })))
                    .then(Commands.literal("area").then(filter().executes { patrol(it, PatrolReaction.AREA, false) }
                        .then(players { context, allow -> patrol(context, PatrolReaction.AREA, allow) }))))))))

    private fun patrolReaction(name: String, reaction: PatrolReaction) = Commands.literal(name)
        .executes { patrol(it, reaction, false) }.then(players { context, allow -> patrol(context, reaction, allow) })
    private fun defend(context: CommandContext<CommandSourceStack>, players: Boolean, mode: String): Int = assign(context) { snapshot ->
        val subject = when (mode) {
            "summoner" -> requireNotNull(snapshot.summonerUuid) { "NPC has no summoner binding" }
            "npc" -> UuidArgument.getUuid(context, "subject")
            else -> null
        }
        DefendTaskDefinition(snapshot.dimensionId, if (subject == null) center(context) else snapshot.position,
            if (mode == "point") 4.0 else radius(context), subject,
            if (subject == null) parseFilter(StringArgumentType.getString(context, "filter")) else NpcEntityTypeFilter.ANY,
            integer(context, "dutyTicks"), snapshot.position, players)
    }
    private fun area(context: CommandContext<CommandSourceStack>, players: Boolean): Int = assign(context) { snapshot ->
        AreaAttackTaskDefinition(snapshot.dimensionId, center(context), radius(context), parseFilter(StringArgumentType.getString(context, "filter")),
            integer(context, "quota"), snapshot.position, players, budget = TaskBudget(ticks = integer(context, "durationTicks")))
    }
    private fun patrol(context: CommandContext<CommandSourceStack>, reaction: PatrolReaction, players: Boolean): Int = assign(context) { snapshot ->
        val subject = when (reaction) {
            PatrolReaction.PROTECT_SUMMONER -> requireNotNull(snapshot.summonerUuid) { "NPC has no summoner binding" }
            else -> null
        }
        PatrolTaskDefinition(snapshot.dimensionId, snapshot.position, radius(context), parseRoute(StringArgumentType.getString(context, "route")),
            integer(context, "rounds"), integer(context, "dwellTicks"), reaction, subject,
            if (reaction == PatrolReaction.SUPPORT) UuidArgument.getUuid(context, "subject") else null,
            if (reaction == PatrolReaction.AREA) parseFilter(StringArgumentType.getString(context, "filter")) else NpcEntityTypeFilter.ANY,
            snapshot.position, players, budget = TaskBudget(ticks = integer(context, "durationTicks")))
    }
    private fun assign(context: CommandContext<CommandSourceStack>, build: (NpcSnapshot) -> TaskDefinition): Int = TaskCommands.withNpc(context) { player, npc ->
        val definition = try { build(npc.snapshot()) } catch (error: IllegalArgumentException) {
            return@withNpc NpcActionResult.rejected(error.message ?: "invalid mission parameters")
        }
        TaskService.assign(player.server, npc, definition)
    }
    internal fun parseFilter(value: String): NpcEntityTypeFilter {
        require(value.length in 1..4096) { "entity filter is empty or too long" }
        val values = value.split(',')
        require(values.size in 1..32 && values.distinct().size == values.size && values.none { it.isBlank() }) { "filter needs 1..32 distinct type IDs or #tag IDs" }
        return NpcEntityTypeFilter.of(values.filterNot { it.startsWith('#') }.toSet(), values.filter { it.startsWith('#') }.map { it.drop(1) }.toSet())
    }
    internal fun parseRoute(value: String): List<NpcPosition> {
        require(value.length in 1..2048) { "patrol route is empty or too long" }
        val points = value.split(';'); require(points.size in 1..16) { "patrol route needs 1..16 x,y,z waypoints" }
        return points.map { point ->
            val coordinates = point.split(','); require(coordinates.size == 3) { "each waypoint needs x,y,z" }
            val numbers = coordinates.map { it.toDoubleOrNull() ?: throw IllegalArgumentException("invalid waypoint coordinate") }
            require(numbers.all { it.isFinite() }) { "waypoint coordinates must be finite" }
            NpcPosition(numbers[0], numbers[1], numbers[2])
        }
    }
    private fun duty(action: (CommandContext<CommandSourceStack>, Boolean) -> Int) = Commands.argument("dutyTicks", IntegerArgumentType.integer(20, 71600))
        .executes { action(it, false) }.then(players(action))
    private fun players(action: (CommandContext<CommandSourceStack>, Boolean) -> Int) = Commands.argument("allowPlayers", BoolArgumentType.bool())
        .executes { action(it, BoolArgumentType.getBool(it, "allowPlayers")) }
    private fun radius() = Commands.argument("radius", DoubleArgumentType.doubleArg(1.0, 32.0))
    private fun filter() = Commands.argument("filter", StringArgumentType.string())
    private fun duration() = Commands.argument("durationTicks", IntegerArgumentType.integer(20, 72000))
    private fun integer(context: CommandContext<CommandSourceStack>, name: String) = IntegerArgumentType.getInteger(context, name)
    private fun radius(context: CommandContext<CommandSourceStack>) = DoubleArgumentType.getDouble(context, "radius")
    private fun center(context: CommandContext<CommandSourceStack>): NpcPosition {
        val point = Vec3Argument.getVec3(context, "center"); return NpcPosition(point.x, point.y, point.z)
    }
}
