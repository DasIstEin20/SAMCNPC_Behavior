package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument

internal object TaskFarmCommands {
    fun branch() = Commands.literal("farm").then(mode(FarmMode.HARVEST)).then(mode(FarmMode.REPLANT)).then(mode(FarmMode.CULTIVATE))
    private fun mode(mode: FarmMode) = Commands.literal(mode.name.lowercase()).then(Commands.argument("crop",StringArgumentType.word())
        .then(Commands.argument("min",BlockPosArgument.blockPos()).then(Commands.argument("max",BlockPosArgument.blockPos())
            .then(Commands.argument("recipient",BlockPosArgument.blockPos()).then(options(mode))))))
    private fun options(mode: FarmMode): RequiredArgumentBuilder<CommandSourceStack,*> {
        val quantity=Commands.argument("quantity",IntegerArgumentType.integer(1,2304))
        if (mode == FarmMode.HARVEST) return quantity.executes { assign(it,mode,6000) }.then(duration(mode))
        val seedKeep=Commands.argument("keepSeeds",IntegerArgumentType.integer(0,512)).executes { assign(it,mode,12000) }.then(duration(mode))
        val prepare=Commands.argument("prepareSoil",BoolArgumentType.bool()).then(seedKeep)
        val cycles=Commands.argument("cycles",IntegerArgumentType.integer(1,8)).then(prepare)
        return quantity.then(cycles)
    }
    private fun duration(mode: FarmMode): RequiredArgumentBuilder<CommandSourceStack,Int> {
        val duration=Commands.argument("durationTicks",IntegerArgumentType.integer(80,72000)).executes { assign(it,mode,integer(it,"durationTicks")) }
        if (mode != FarmMode.HARVEST) duration.then(Commands.argument("seedSource",BlockPosArgument.blockPos())
            .then(Commands.argument("sourceKeepSeeds",IntegerArgumentType.integer(0,2304)).executes { assign(it,mode,integer(it,"durationTicks"),true) }))
        return duration
    }
    private fun assign(c: CommandContext<CommandSourceStack>,mode: FarmMode,duration: Int,source: Boolean=false): Int = TaskCommands.withNpc(c) { player,npc ->
        val snapshot=npc.snapshot()
        val definition=try {
            val work=FarmWorkOrder(WorkArea(WorkBox(block(c,"min"),block(c,"max"))),enumValueOf(StringArgumentType.getString(c,"crop").uppercase()),mode,
                cycles=if (mode == FarmMode.HARVEST) 1 else integer(c,"cycles"),prepareSoil=mode != FarmMode.HARVEST && BoolArgumentType.getBool(c,"prepareSoil"),
                seedSources=if (source) ContainerChoices(listOf(block(c,"seedSource"))) else null,keepSeeds=if (mode == FarmMode.HARVEST) 0 else integer(c,"keepSeeds"),
                sourceKeepSeeds=if (source) integer(c,"sourceKeepSeeds") else 0)
            FarmTaskDefinition(snapshot.dimensionId,work,ContainerChoices(listOf(block(c,"recipient"))),integer(c,"quantity"),snapshot.position,
                returnTo=snapshot.position,budget=TaskBudget(duration))
        } catch (error: IllegalArgumentException) { return@withNpc NpcActionResult.rejected(error.message ?: "invalid bounded farm order") }
        TaskService.assign(player.server,npc,definition)
    }
    private fun integer(c: CommandContext<CommandSourceStack>,name: String)=IntegerArgumentType.getInteger(c,name)
    private fun block(c: CommandContext<CommandSourceStack>,name: String): NpcBlockPosition { val p=BlockPosArgument.getBlockPos(c,name); return NpcBlockPosition(p.x,p.y,p.z) }
}
