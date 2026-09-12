package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument

internal object TaskTransportCommands {
    fun branch() = Commands.literal("transport").then(Commands.argument("source", BlockPosArgument.blockPos())
        .then(Commands.argument("recipient", BlockPosArgument.blockPos()).then(item(false))))
    fun alternativesBranch() = Commands.literal("transport_many").then(Commands.argument("sources", StringArgumentType.string())
        .then(Commands.argument("recipients", StringArgumentType.string()).then(item(true))))
    private fun item(many: Boolean) = Commands.argument("item", ResourceLocationArgument.id())
        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 2304))
            .executes { assign(it, many) }
            .then(Commands.literal("nearest").executes { assign(it, many, preference = ContainerPreference.NEAREST) })
            .then(Commands.argument("keepAtLeast", IntegerArgumentType.integer(0, 2304)).executes { assign(it, many, integer(it, "keepAtLeast")) }
                .then(Commands.argument("sourceKeepAtLeast", IntegerArgumentType.integer(0, 2304)).executes { assign(it, many, integer(it, "keepAtLeast"), integer(it, "sourceKeepAtLeast")) }
                    .then(Commands.argument("durationTicks", IntegerArgumentType.integer(20, 72000)).executes {
                        assign(it, many, integer(it, "keepAtLeast"), integer(it, "sourceKeepAtLeast"), integer(it, "durationTicks"))
                    }.then(Commands.argument("returnToStart", BoolArgumentType.bool()).executes {
                        assign(it, many, integer(it, "keepAtLeast"), integer(it, "sourceKeepAtLeast"), integer(it, "durationTicks"), BoolArgumentType.getBool(it, "returnToStart"))
                    })))))
    private fun assign(context: CommandContext<CommandSourceStack>, many: Boolean, reserve: Int = 0, sourceReserve: Int = 0,
                       duration: Int = 6000, returnToStart: Boolean = false, preference: ContainerPreference = ContainerPreference.ORDERED): Int =
        TaskCommands.withNpc(context) { player, npc ->
            val snapshot = npc.snapshot()
            val definition = try {
                val sources = if (many) parsePositions(StringArgumentType.getString(context, "sources")) else listOf(block(context, "source"))
                val recipients = if (many) parsePositions(StringArgumentType.getString(context, "recipients")) else listOf(block(context, "recipient"))
                TransportTaskDefinition(snapshot.dimensionId, ContainerChoices(sources, preference), ContainerChoices(recipients, preference),
                    ResourceLocationArgument.getId(context, "item").toString(), integer(context, "quantity"), snapshot.position,
                    keepAtLeast = reserve, sourceKeepAtLeast = sourceReserve, returnTo = if (returnToStart) snapshot.position else null, budget = TaskBudget(ticks = duration))
            } catch (error: IllegalArgumentException) {
                return@withNpc NpcActionResult.rejected(error.message ?: "invalid transport parameters")
            }
            TaskService.assign(player.server, npc, definition)
        }
    internal fun parsePositions(value: String): List<NpcBlockPosition> {
        require(value.length in 1..512) { "container list is empty or too long" }
        val parts = value.split(';'); require(parts.size in 1..8) { "container list needs 1..8 absolute x,y,z positions" }
        val positions = parts.map { part ->
            val values = part.split(','); require(values.size == 3) { "each container needs x,y,z" }
            val numbers = values.map { it.toIntOrNull() ?: throw IllegalArgumentException("container coordinates must be integers") }
            NpcBlockPosition(numbers[0], numbers[1], numbers[2])
        }
        require(positions.distinct().size == positions.size) { "duplicate container position" }
        return positions
    }
    private fun integer(context: CommandContext<CommandSourceStack>, name: String) = IntegerArgumentType.getInteger(context, name)
    private fun block(context: CommandContext<CommandSourceStack>, name: String): NpcBlockPosition {
        val position = BlockPosArgument.getBlockPos(context, name); return NpcBlockPosition(position.x, position.y, position.z)
    }
}
