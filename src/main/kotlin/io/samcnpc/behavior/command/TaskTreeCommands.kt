package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument

internal object TaskTreeCommands {
    fun branch()=Commands.literal("plant_trees").then(plantMode(PlantingMode.PATCH)).then(plantMode(PlantingMode.GAPS))
    private fun plantMode(mode: PlantingMode): ArgumentBuilder<CommandSourceStack,*> {
        val reserve=Commands.argument("keepSaplings",IntegerArgumentType.integer(0,512)).executes { plant(it,mode,6000) }
        val duration=Commands.argument("durationTicks",IntegerArgumentType.integer(80,72000)).executes { plant(it,mode,integer(it,"durationTicks")) }
        duration.then(Commands.argument("saplingSources",StringArgumentType.string()).then(Commands.argument("sourceKeep",IntegerArgumentType.integer(0,2304)).executes { plant(it,mode,integer(it,"durationTicks"),true) }))
        reserve.then(duration)
        return sequence(Commands.literal(mode.name.lowercase()),Commands.argument("species",StringArgumentType.word()),
            Commands.argument("plantMin",BlockPosArgument.blockPos()),Commands.argument("plantMax",BlockPosArgument.blockPos()),
            Commands.argument("spacing",IntegerArgumentType.integer(3,16)),Commands.argument("layouts",IntegerArgumentType.integer(1,128)),reserve)
    }
    fun woodBranch(): ArgumentBuilder<CommandSourceStack,*> {
        val keep=Commands.argument("sourceKeep",IntegerArgumentType.integer(0,2304)).executes { wood(it,6000) }
            .then(Commands.argument("durationTicks",IntegerArgumentType.integer(80,72000)).executes { wood(it,integer(it,"durationTicks")) })
        return sequence(Commands.literal("lumberjack_replant"),Commands.argument("workMin",BlockPosArgument.blockPos()),Commands.argument("workMax",BlockPosArgument.blockPos()),
            Commands.argument("output",BlockPosArgument.blockPos()),Commands.argument("woodSupplies",StringArgumentType.string()),Commands.argument("wood",ResourceLocationArgument.id()),
            Commands.argument("quantity",IntegerArgumentType.integer(1,2304)),Commands.argument("species",StringArgumentType.word()),
            Commands.argument("plantMin",BlockPosArgument.blockPos()),Commands.argument("plantMax",BlockPosArgument.blockPos()),Commands.argument("spacing",IntegerArgumentType.integer(3,16)),
            Commands.argument("layouts",IntegerArgumentType.integer(1,128)),Commands.argument("keepSaplings",IntegerArgumentType.integer(0,512)),Commands.argument("saplingSources",StringArgumentType.string()),keep)
    }
    private fun plant(c: CommandContext<CommandSourceStack>,mode: PlantingMode,duration: Int,source: Boolean=false): Int=TaskCommands.withNpc(c) { player,npc ->
        val definition=try { definition(c,npc,mode,duration,source) } catch(error: IllegalArgumentException) { return@withNpc NpcActionResult.rejected(error.message ?: "invalid planting order") }
        TaskService.assign(player.server,npc,definition)
    }
    private fun wood(c: CommandContext<CommandSourceStack>,duration: Int): Int=TaskCommands.withNpc(c) { player,npc ->
        val definition=try {
            LumberjackTaskDefinition(npc.snapshot().dimensionId,WorkArea(WorkBox(block(c,"workMin"),block(c,"workMax"))),
                WoodSelection(listOf(ResourceLocationArgument.getId(c,"wood").toString())),block(c,"output"),integer(c,"quantity"),budget=TaskBudget(duration),version=2,
                supplySources=sources(c,"woodSupplies"),replant=definition(c,npc,PlantingMode.GAPS,duration,true))
        } catch(error: IllegalArgumentException) { return@withNpc NpcActionResult.rejected(error.message ?: "invalid wood replant order") }
        TaskService.assign(player.server,npc,definition)
    }
    private fun definition(c: CommandContext<CommandSourceStack>,npc: NpcFacade,mode: PlantingMode,duration: Int,source: Boolean): PlantingTaskDefinition {
        val snapshot=npc.snapshot()
        val work=PlantingWorkOrder(WorkArea(WorkBox(block(c,"plantMin"),block(c,"plantMax"))),enumValueOf(StringArgumentType.getString(c,"species").uppercase()),mode,integer(c,"spacing"),
            sources=if(source) sources(c,"saplingSources") else null,keepSaplings=integer(c,"keepSaplings"),sourceKeep=if(source) integer(c,"sourceKeep") else 0)
        return PlantingTaskDefinition(snapshot.dimensionId,work,integer(c,"layouts"),snapshot.position,returnTo=snapshot.position,budget=TaskBudget(duration))
    }
    private fun sources(c: CommandContext<CommandSourceStack>,name: String): ContainerChoices? {
        val text=StringArgumentType.getString(c,name)
        return if(text == "none") null else ContainerChoices(TaskTransportCommands.parsePositions(text))
    }
    private fun integer(c: CommandContext<CommandSourceStack>,name: String)=IntegerArgumentType.getInteger(c,name)
    private fun block(c: CommandContext<CommandSourceStack>,name: String): NpcBlockPosition { val p=BlockPosArgument.getBlockPos(c,name); return NpcBlockPosition(p.x,p.y,p.z) }
    private fun sequence(vararg nodes: ArgumentBuilder<CommandSourceStack,*>): ArgumentBuilder<CommandSourceStack,*> {
        for(i in nodes.size-2 downTo 0) nodes[i].then(nodes[i+1])
        return nodes.first()
    }
}
