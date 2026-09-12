package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.combat.*
import io.samcnpc.behavior.task.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

internal object TaskTacticsCommands {
    fun branch() = Commands.literal("tactics").then(Commands.argument("npc", StringArgumentType.word())
        .then(Commands.literal("auto").executes { change(it) { CombatTactics() } })
        .then(Commands.literal("legacy").executes { change(it) { CombatTactics.LEGACY } })
        .then(Commands.literal("preference").apply {
            for (value in CombatWeaponPreference.entries) then(Commands.literal(value.name.lowercase()).executes { change(it) { old -> old.copy(preference = value) } })
        })
        .then(Commands.literal("allowed").apply {
            for (value in CombatWeaponAllowance.entries) then(Commands.literal(value.name.lowercase()).executes { change(it) { old -> old.copy(allowed = value) } })
        })
        .then(Commands.literal("armor").then(Commands.argument("value", BoolArgumentType.bool()).executes { context -> change(context) { it.copy(equipArmor = flag(context)) } }))
        .then(Commands.literal("shield").then(Commands.argument("value", BoolArgumentType.bool()).executes { context -> change(context) { it.copy(useShield = flag(context)) } }))
        .then(Commands.literal("heal").then(Commands.argument("value", BoolArgumentType.bool()).executes { context -> change(context) { it.copy(heal = flag(context)) } }))
        .then(Commands.literal("retreat").then(Commands.argument("below", DoubleArgumentType.doubleArg(0.0, 0.9))
            .then(Commands.argument("returnAt", DoubleArgumentType.doubleArg(0.1, 1.0)).executes { context ->
                change(context) { it.copy(retreatAt = number(context, "below"), returnAt = number(context, "returnAt")) }
            })))
        .then(Commands.literal("range").then(Commands.argument("minimum", DoubleArgumentType.doubleArg(2.5, 12.0))
            .then(Commands.argument("maximum", DoubleArgumentType.doubleArg(4.0, 24.0)).executes { context ->
                change(context) { it.copy(rangedMinDistance = number(context, "minimum"), rangedMaxDistance = number(context, "maximum")) }
            }))))
    private fun change(context: CommandContext<CommandSourceStack>, change: (CombatTactics) -> CombatTactics): Int =
        TaskCommands.withNpc(context) { player, npc ->
            val record = TaskStore.forServer(player.server).get(npc.npcUuid)
                ?: return@withNpc io.samcnpc.core.api.NpcActionResult.rejected("no assigned task")
            TaskAmendments.automatic(player.server, npc, player, TaskChange.Tactics(change(TaskTacticsControl.current(record))))
        }
    private fun flag(context: CommandContext<CommandSourceStack>) = BoolArgumentType.getBool(context, "value")
    private fun number(context: CommandContext<CommandSourceStack>, name: String) = DoubleArgumentType.getDouble(context, name)
}
