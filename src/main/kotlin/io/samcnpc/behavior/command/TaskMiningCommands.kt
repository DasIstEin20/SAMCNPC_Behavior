package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument

internal object TaskMiningCommands {
    fun branch() = Commands.literal("mine")
        .then(area(MiningMethod.EXPOSED)).then(area(MiningMethod.VEIN)).then(area(MiningMethod.EXCAVATION))
        .then(Commands.literal("tunnel").then(Commands.argument("origin",BlockPosArgument.blockPos())
            .then(Commands.argument("direction",StringArgumentType.word()).then(Commands.argument("width",IntegerArgumentType.integer(1,3))
                .then(Commands.argument("height",IntegerArgumentType.integer(2,4)).then(Commands.argument("length",IntegerArgumentType.integer(1,32))
                    .then(recipient(MiningMethod.TUNNEL))))))))
    private fun area(method: MiningMethod) = Commands.literal(method.name.lowercase()).then(Commands.argument("min",BlockPosArgument.blockPos())
        .then(Commands.argument("max",BlockPosArgument.blockPos()).then(recipient(method))))
    private fun recipient(method: MiningMethod) = Commands.argument("recipient",BlockPosArgument.blockPos())
        .then(Commands.argument("resourceBlocks",StringArgumentType.string()).then(Commands.argument("accessBlocks",StringArgumentType.string())
            .then(Commands.argument("outputItems",StringArgumentType.string()).then(Commands.argument("counting",StringArgumentType.word())
                .then(Commands.argument("quantity",IntegerArgumentType.integer(1,2304)).executes { assign(it,method,6000,true) }
                    .then(Commands.argument("durationTicks",IntegerArgumentType.integer(20,72000)).executes { assign(it,method,integer(it,"durationTicks"),true) }
                        .then(Commands.argument("returnToStart",BoolArgumentType.bool()).executes { assign(it,method,integer(it,"durationTicks"),BoolArgumentType.getBool(it,"returnToStart")) })))))))
    private fun assign(context: CommandContext<CommandSourceStack>,method: MiningMethod,duration: Int,returnToStart: Boolean): Int = TaskCommands.withNpc(context) { player,npc ->
        val snapshot=npc.snapshot()
        val definition=try {
            val tunnel=if (method == MiningMethod.TUNNEL) TunnelGeometry(block(context,"origin"),enumValueOf(word(context,"direction").uppercase()),integer(context,"width"),integer(context,"height"),integer(context,"length")) else null
            val area=WorkArea(tunnel?.bounds() ?: WorkBox(block(context,"min"),block(context,"max")))
            val access=word(context,"accessBlocks")
            val work=MiningWorkOrder(area,method,ids(word(context,"resourceBlocks")),if (access == "-") null else ids(access),tunnel)
            MiningTaskDefinition(snapshot.dimensionId,work,ids(word(context,"outputItems")),ContainerChoices(listOf(block(context,"recipient"))),
                integer(context,"quantity"),enumValueOf(word(context,"counting").uppercase()),snapshot.position,
                returnTo=if (returnToStart) snapshot.position else null,budget=TaskBudget(ticks=duration))
        } catch (error: IllegalArgumentException) { return@withNpc NpcActionResult.rejected(error.message ?: "invalid mining order; use EXPOSED/VEIN/TUNNEL/EXCAVATION and DELIVERED_ITEMS/REMOVED_RESOURCE_BLOCKS/CLEARED_VOLUME") }
        TaskService.assign(player.server,npc,definition)
    }
    internal fun ids(value: String): WorkResourceIds { require(value.length in 1..4096) { "mining ID list exceeds bounds" }; return WorkResourceIds(value.split(',')) }
    private fun word(c: CommandContext<CommandSourceStack>,name: String)=StringArgumentType.getString(c,name)
    private fun integer(c: CommandContext<CommandSourceStack>,name: String)=IntegerArgumentType.getInteger(c,name)
    private fun block(c: CommandContext<CommandSourceStack>,name: String): NpcBlockPosition { val p=BlockPosArgument.getBlockPos(c,name); return NpcBlockPosition(p.x,p.y,p.z) }
}
