package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import java.util.Locale

internal object TaskMachineCommands {
    fun branch() = Commands.literal("machine")
        .then(Commands.argument("machine",BlockPosArgument.blockPos())
            .then(Commands.argument("feeds",StringArgumentType.string())
                .then(Commands.argument("output",StringArgumentType.string())
                    .executes { execute(it,20,1200,6000,32.0) }
                    .then(Commands.argument("pollTicks",IntegerArgumentType.integer(5,200))
                        .then(Commands.argument("noProgressTicks",IntegerArgumentType.integer(5,72000))
                            .then(Commands.argument("durationTicks",IntegerArgumentType.integer(20,72000))
                                .then(Commands.argument("travelRadius",DoubleArgumentType.doubleArg(4.0,64.0))
                                    .executes { execute(it,IntegerArgumentType.getInteger(it,"pollTicks"),
                                        IntegerArgumentType.getInteger(it,"noProgressTicks"),IntegerArgumentType.getInteger(it,"durationTicks"),
                                        DoubleArgumentType.getDouble(it,"travelRadius")) })))))))
    private fun execute(context: CommandContext<CommandSourceStack>,poll: Int,idle: Int,duration: Int,radius: Double): Int = TaskCommands.withNpc(context) { player,npc ->
        try {
            val position=BlockPosArgument.getBlockPos(context,"machine")
            val snapshot=npc.snapshot()
            val endpoint=NpcContainerEndpoint(snapshot.dimensionId,NpcBlockPosition(position.x,position.y,position.z))
            val feeds=parsePorts(StringArgumentType.getString(context,"feeds"),endpoint,4)
            val output=parsePorts(StringArgumentType.getString(context,"output"),endpoint,1).single()
            TaskService.assign(player.server,npc,MachineTaskDefinition(snapshot.dimensionId,MachineFeeds(feeds),output,snapshot.position,
                radius,snapshot.position,poll,idle,TaskBudget(duration)))
        } catch (error: IllegalArgumentException) { NpcActionResult.rejected(error.message ?: "invalid bounded machine contract") }
    }
    internal fun parsePorts(text: String,endpoint: NpcContainerEndpoint,limit: Int): List<MachinePort> {
        require(text.length in 1..2048) { "machine ports are empty or oversized" }
        val rows=text.split(';');require(rows.size in 1..limit) { "expected one to $limit machine ports" }
        return rows.map { row ->
            val parts=row.split(',');require(parts.size == 4) { "each machine port uses face,slot,item,quantity; separate inputs with semicolons" }
            val side=parts[0].uppercase(Locale.ROOT)
            val face=if (side == "NONE") null else NpcBlockFace.entries.firstOrNull { it.name == side } ?: throw IllegalArgumentException("unknown automation face")
            val slot=parts[1].toIntOrNull() ?: throw IllegalArgumentException("machine slot must be an integer")
            val count=parts[3].toIntOrNull() ?: throw IllegalArgumentException("machine quantity must be an integer")
            MachinePort(endpoint.copy(side=face),slot,parts[2],count)
        }
    }
}
