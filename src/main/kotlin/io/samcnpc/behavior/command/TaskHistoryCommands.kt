package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

internal object TaskHistoryCommands {
    fun history() = Commands.literal("history").then(Commands.argument("npc", StringArgumentType.word())
        .executes { show(it) { record -> TaskHistory.objectives(record) } }
        .then(Commands.argument("objective", IntegerArgumentType.integer(1, 8))
            .executes { context -> show(context) { TaskHistory.objectives(it, number(context, "objective")) } }
            .then(Commands.argument("page", IntegerArgumentType.integer(1, 264)).executes { context ->
                show(context) { TaskHistory.objectives(it, number(context, "objective"), number(context, "page")) }
            })))
    fun amendments() = Commands.literal("amendments").then(Commands.argument("npc", StringArgumentType.word())
        .executes { show(it) { record -> TaskHistory.amendments(record) } }
        .then(Commands.argument("page", IntegerArgumentType.integer(1, 4)).executes { context -> show(context) { TaskHistory.amendments(it, number(context, "page")) } }))
    fun inventory() = Commands.literal("inventory_history").then(Commands.argument("npc",StringArgumentType.word())
        .executes { show(it) { record -> TaskHistory.inventory(record) } }
        .then(Commands.argument("outcome",IntegerArgumentType.integer(1,32))
            .executes { context -> show(context) { TaskHistory.inventory(it,number(context,"outcome")) } }
            .then(Commands.argument("page",IntegerArgumentType.integer(1,35)).executes { context ->
                show(context) { TaskHistory.inventory(it,number(context,"outcome"),number(context,"page")) }
            })))
    private fun show(context: CommandContext<CommandSourceStack>, format: (TaskRecord) -> String): Int = TaskCommands.withNpc(context) { player, npc ->
        val record = TaskStore.forServer(player.server).get(npc.npcUuid) ?: return@withNpc NpcActionResult.rejected("no durable task", NpcActionCode.NOT_READY)
        try { NpcActionResult.succeeded(format(record)) }
        catch (error: IllegalArgumentException) { NpcActionResult.rejected(error.message ?: "invalid report page") }
    }
    private fun number(context: CommandContext<CommandSourceStack>, name: String) = IntegerArgumentType.getInteger(context, name)
}
