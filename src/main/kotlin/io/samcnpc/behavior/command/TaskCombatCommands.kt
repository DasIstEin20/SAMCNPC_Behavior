package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.UuidArgument

/** Manual exact attack and explicit task reaction configuration use the same checked NPC context. */
internal object TaskCombatCommands {
    fun attackBranch() = Commands.literal("attack")
        .then(Commands.argument("target", UuidArgument.uuid())
            .executes { attack(it, 24.0, 600, false) }
            .then(Commands.argument("leash", DoubleArgumentType.doubleArg(1.0, 32.0))
                .executes { attack(it, leash(it), 600, false) }
                .then(Commands.argument("durationTicks", IntegerArgumentType.integer(20, 2400))
                    .executes { attack(it, leash(it), duration(it), false) }
                    .then(Commands.argument("allowPlayers", BoolArgumentType.bool())
                        .executes { attack(it, leash(it), duration(it), players(it)) }))))

    private fun attack(context: CommandContext<CommandSourceStack>, leash: Double, duration: Int, players: Boolean): Int =
        TaskCommands.withNpc(context) { player, npc ->
            val snapshot = npc.snapshot()
            val definition = AttackTaskDefinition(snapshot.dimensionId, UuidArgument.getUuid(context, "target"), snapshot.position,
                leash, players, TaskBudget(ticks = duration))
            TaskService.assign(player.server, npc, definition)
        }

    private fun leash(context: CommandContext<CommandSourceStack>) = DoubleArgumentType.getDouble(context, "leash")
    private fun duration(context: CommandContext<CommandSourceStack>) = IntegerArgumentType.getInteger(context, "durationTicks")
    private fun players(context: CommandContext<CommandSourceStack>) = BoolArgumentType.getBool(context, "allowPlayers")
}
