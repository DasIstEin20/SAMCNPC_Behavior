package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument

/** Vanilla argument types keep the command tree serializable for ordinary Forge clients. */
internal object TaskFoodCommands {
    fun branch() = Commands.literal("food")
        .then(area("drops")).then(area("berries")).then(area("hunt"))
        .then(Commands.literal("stored").then(Commands.argument("source",BlockPosArgument.blockPos())
            .then(Commands.argument("sourceKeep",IntegerArgumentType.integer(0,2304)).then(recipient("stored")))))
    private fun area(mode: String) = Commands.literal(mode).then(Commands.argument("min",BlockPosArgument.blockPos())
        .then(Commands.argument("max",BlockPosArgument.blockPos()).then(
            if (mode == "hunt") Commands.argument("targetTypes",StringArgumentType.string()).then(Commands.argument("huntLimit",IntegerArgumentType.integer(1,16)).then(recipient(mode))) else recipient(mode))))
    private fun recipient(mode: String) = Commands.argument("recipient",BlockPosArgument.blockPos())
        .then(Commands.argument("foodItems",StringArgumentType.string()).then(Commands.argument("quantity",IntegerArgumentType.integer(1,2304))
            .then(Commands.argument("keepFood",IntegerArgumentType.integer(0,64)).executes { assign(it,mode,6000,true) }
                .then(Commands.argument("durationTicks",IntegerArgumentType.integer(20,72000)).executes { assign(it,mode,integer(it,"durationTicks"),true) }
                    .then(Commands.argument("returnToStart",BoolArgumentType.bool()).executes { assign(it,mode,integer(it,"durationTicks"),BoolArgumentType.getBool(it,"returnToStart")) })))))
    private fun assign(context: CommandContext<CommandSourceStack>,mode: String,duration: Int,returnToStart: Boolean): Int = TaskCommands.withNpc(context) { player,npc ->
        val snapshot=npc.snapshot()
        val definition=try {
            val area=if (mode == "stored") null else WorkArea(WorkBox(block(context,"min"),block(context,"max")))
            val work=when (mode) {
                "drops" -> FoodWorkOrder.Drops(checkNotNull(area))
                "berries" -> FoodWorkOrder.Berries(checkNotNull(area))
                "stored" -> FoodWorkOrder.Stored(ContainerChoices(listOf(block(context,"source"))),integer(context,"sourceKeep"))
                "hunt" -> FoodWorkOrder.Hunt(checkNotNull(area),NpcEntityTypeFilter.of(typeIds=TaskMiningCommands.ids(word(context,"targetTypes")).values.toSet()),integer(context,"huntLimit"))
                else -> error("unregistered food command")
            }
            FoodTaskDefinition(snapshot.dimensionId,work,TaskMiningCommands.ids(word(context,"foodItems")),ContainerChoices(listOf(block(context,"recipient"))),
                integer(context,"quantity"),integer(context,"keepFood"),snapshot.position,returnTo=if (returnToStart) snapshot.position else null,budget=TaskBudget(ticks=duration))
        } catch (error: IllegalArgumentException) { return@withNpc NpcActionResult.rejected(error.message ?: "invalid bounded food order") }
        TaskService.assign(player.server,npc,definition)
    }
    private fun word(c: CommandContext<CommandSourceStack>,name: String)=StringArgumentType.getString(c,name)
    private fun integer(c: CommandContext<CommandSourceStack>,name: String)=IntegerArgumentType.getInteger(c,name)
    private fun block(c: CommandContext<CommandSourceStack>,name: String): NpcBlockPosition { val p=BlockPosArgument.getBlockPos(c,name); return NpcBlockPosition(p.x,p.y,p.z) }
}
