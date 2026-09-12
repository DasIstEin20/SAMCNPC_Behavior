package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.combat.CombatTactics
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.UuidArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument

internal object TaskReactionCommands {
    fun branch() = Commands.literal("reaction").then(Commands.argument("npc", StringArgumentType.word())
        .then(Commands.literal("passive").executes { configure(it, TaskReactionMode.PASSIVE) })
        .then(Commands.literal("retaliate").executes { configure(it, TaskReactionMode.RETALIATE) }
            .then(leash().executes { configure(it, TaskReactionMode.RETALIATE, leash(it)) }
                .then(duration(TaskReactionMode.RETALIATE))))
        .then(Commands.literal("protect_summoner").then(leash().then(duration(TaskReactionMode.PROTECT_SUMMONER))))
        .then(Commands.literal("protect").then(Commands.argument("subject", UuidArgument.uuid())
            .then(leash().then(duration(TaskReactionMode.PROTECT_UNIT)))))
        .then(Commands.literal("area").then(Commands.argument("center", Vec3Argument.vec3())
            .then(leash().then(Commands.argument("filter", StringArgumentType.string()).then(duration(TaskReactionMode.AREA)))))))

    private fun duration(mode: TaskReactionMode) = Commands.argument("durationTicks", IntegerArgumentType.integer(20, 2400))
        .executes { configure(it, mode, leash(it), IntegerArgumentType.getInteger(it, "durationTicks")) }
        .then(Commands.argument("allowPlayers", BoolArgumentType.bool()).executes {
            configure(it, mode, leash(it), IntegerArgumentType.getInteger(it, "durationTicks"), BoolArgumentType.getBool(it, "allowPlayers"))
        })
    private fun leash() = Commands.argument("leash", DoubleArgumentType.doubleArg(1.0, 32.0))
    private fun leash(context: CommandContext<CommandSourceStack>) = DoubleArgumentType.getDouble(context, "leash")
    private fun configure(context: CommandContext<CommandSourceStack>, mode: TaskReactionMode, leash: Double = 24.0, duration: Int = 600, players: Boolean = false): Int =
        TaskCommands.withNpc(context) { player, npc ->
            val snapshot = npc.snapshot()
            val previous = TaskStore.forServer(player.server).get(npc.npcUuid)
            val policy = try {
                val subject = when (mode) {
                    TaskReactionMode.PROTECT_SUMMONER -> requireNotNull(snapshot.summonerUuid) { "NPC has no summoner binding" }
                    TaskReactionMode.PROTECT_UNIT -> UuidArgument.getUuid(context, "subject")
                    else -> null
                }
                val anchor = if (mode == TaskReactionMode.AREA) {
                    val point = Vec3Argument.getVec3(context, "center"); NpcPosition(point.x, point.y, point.z)
                } else if (subject != null) snapshot.position else null
                TaskReactionPolicy(mode, leash, duration, allowPlayers = players, tactics = previous?.let(TaskTacticsControl::current) ?: CombatTactics.LEGACY,
                    anchor = anchor, subjectUuid = subject, filter = if (mode == TaskReactionMode.AREA)
                        TaskMissionCommands.parseFilter(StringArgumentType.getString(context, "filter")) else NpcEntityTypeFilter.ANY)
            } catch (error: IllegalArgumentException) {
                return@withNpc NpcActionResult.rejected(error.message ?: "invalid reaction parameters")
            }
            TaskAmendments.automatic(player.server, npc, player, TaskChange.Reaction(policy))
        }
}
