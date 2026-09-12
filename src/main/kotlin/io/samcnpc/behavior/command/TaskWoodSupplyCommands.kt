package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument

internal object TaskWoodSupplyCommands {
    fun branch() = Commands.literal("lumberjack_supplied")
        .then(Commands.argument("workMin", BlockPosArgument.blockPos())
            .then(Commands.argument("workMax", BlockPosArgument.blockPos())
                .then(Commands.argument("output", BlockPosArgument.blockPos())
                    .then(Commands.argument("supplies", StringArgumentType.string())
                        .then(Commands.argument("wood", ResourceLocationArgument.id())
                            .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 2304)).executes { assign(it, 6000) }
                                .then(Commands.argument("durationTicks", IntegerArgumentType.integer(20, 72000))
                                    .executes { assign(it, IntegerArgumentType.getInteger(it, "durationTicks")) })))))))
    private fun assign(context: CommandContext<CommandSourceStack>, duration: Int): Int = TaskCommands.withNpc(context) { player, npc ->
        val supplies = try {
            val text = StringArgumentType.getString(context, "supplies")
            if (text == "none") null else ContainerChoices(TaskTransportCommands.parsePositions(text))
        } catch (error: IllegalArgumentException) {
            return@withNpc NpcActionResult.rejected(error.message ?: "invalid wood supply sources")
        }
        val definition = LumberjackTaskDefinition(npc.snapshot().dimensionId, WorkArea(WorkBox(block(context, "workMin"), block(context, "workMax"))),
            WoodSelection(listOf(ResourceLocationArgument.getId(context, "wood").toString())), block(context, "output"),
            IntegerArgumentType.getInteger(context, "quantity"), budget = TaskBudget(ticks = duration), version = 2, supplySources = supplies)
        TaskService.assign(player.server, npc, definition)
    }
    private fun block(context: CommandContext<CommandSourceStack>, name: String): NpcBlockPosition {
        val p = BlockPosArgument.getBlockPos(context, name); return NpcBlockPosition(p.x, p.y, p.z)
    }
}
