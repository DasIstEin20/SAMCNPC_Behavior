package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.ExplorerTaskDefinition
import io.samcnpc.behavior.task.TaskBudget
import io.samcnpc.behavior.task.TaskService
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

internal object TaskExplorerCommands {
    fun branch() = Commands.literal("explore")
        .then(Commands.argument("radius",IntegerArgumentType.integer(8,96))
            .then(Commands.argument("maxCells",IntegerArgumentType.integer(1,64))
                .executes { execute(it,false) }
                .then(Commands.argument("cellStep",IntegerArgumentType.integer(4,8))
                    .then(Commands.argument("verticalRange",IntegerArgumentType.integer(1,16))
                        .then(Commands.argument("chunkBudget",IntegerArgumentType.integer(9,256))
                            .then(Commands.argument("heading",IntegerArgumentType.integer(0,3))
                                .then(Commands.argument("durationTicks",IntegerArgumentType.integer(40,72000))
                                    .executes { execute(it,true) })))))))

    private fun execute(context: CommandContext<CommandSourceStack>,explicit: Boolean): Int = TaskCommands.withNpc(context) { player,npc ->
        val snapshot=npc.snapshot()
        fun value(name: String,default: Int) = if (explicit) IntegerArgumentType.getInteger(context,name) else default
        TaskService.assign(player.server,npc,ExplorerTaskDefinition(snapshot.dimensionId,snapshot.position,
            radius=IntegerArgumentType.getInteger(context,"radius"),maxCells=IntegerArgumentType.getInteger(context,"maxCells"),
            cellStep=value("cellStep",4),verticalRange=value("verticalRange",12),chunkBudget=value("chunkBudget",64),
            heading=value("heading",0),budget=TaskBudget(value("durationTicks",6000))))
    }
}
