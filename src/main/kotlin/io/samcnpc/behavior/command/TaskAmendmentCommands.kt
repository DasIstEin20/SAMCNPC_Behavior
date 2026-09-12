package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.commands.arguments.UuidArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument

internal object TaskAmendmentCommands {
    fun branch() = Commands.literal("amend").then(operations(Commands.argument("npc", StringArgumentType.word()), false))
    fun checkedBranch() = Commands.literal("amend_checked").then(Commands.argument("npc", StringArgumentType.word())
        .then(Commands.argument("taskId", UuidArgument.uuid()).then(Commands.argument("revision", IntegerArgumentType.integer(0, 32))
            .then(Commands.argument("requestId", UuidArgument.uuid()).then(operations(Commands.argument("expiresTick", LongArgumentType.longArg(1200)), true))))))
    private fun <T : ArgumentBuilder<CommandSourceStack, T>> operations(parent: T, checked: Boolean): T = parent
        .then(Commands.literal("quantity").apply {
            for (mode in QuantityChangeMode.entries) then(Commands.literal(mode.name.lowercase()).then(Commands.argument("amount", IntegerArgumentType.integer(1, 2304)).executes { context ->
                amend(context, checked) { TaskChange.Quantity(integer(context, "amount"), mode) }
            }))
        })
        .then(Commands.literal("redirect_remaining").then(Commands.argument("recipients", StringArgumentType.string()).executes { context ->
            amend(context, checked) { TaskChange.Redirect(ContainerChoices(TaskTransportCommands.parsePositions(StringArgumentType.getString(context, "recipients")))) }
        }))
        .then(Commands.literal("sources").then(Commands.argument("sources", StringArgumentType.string()).executes { context ->
            amend(context, checked) {
                val value = StringArgumentType.getString(context, "sources")
                TaskChange.Sources(if (value == "none") null else ContainerChoices(TaskTransportCommands.parsePositions(value)))
            }
        }))
        .then(Commands.literal("new_resource_objective").then(Commands.argument("item", ResourceLocationArgument.id())
            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 2304)).executes { context -> amend(context, checked) { old ->
                val item = ResourceLocationArgument.getId(context, "item").toString(); val quantity = integer(context, "amount")
                TaskChange.Replace(when (old) {
                    is TransportTaskDefinition -> old.copy(itemId = item, quantity = quantity)
                    is DeliveryTaskDefinition -> old.copy(itemId = item, quantity = quantity)
                    is LumberjackTaskDefinition -> old.copy(wood = WoodSelection(listOf(item)), quantity = quantity)
                    else -> throw IllegalArgumentException("operation has no item/wood objective")
                }, ObjectiveChangeMode.NEW_OBJECTIVE)
            } })))
        .then(Commands.literal("navigate").then(Commands.argument("position", Vec3Argument.vec3()).executes { context -> amend(context, checked) { old ->
            require(old is NavigateTaskDefinition) { "navigate amendment requires a navigation operation" }
            val p = Vec3Argument.getVec3(context, "position"); TaskChange.Replace(old.copy(destination = NpcPosition(p.x, p.y, p.z)))
        } }))
        .then(Commands.literal("work_area").then(Commands.argument("minimum", BlockPosArgument.blockPos()).then(Commands.argument("maximum", BlockPosArgument.blockPos()).executes { context -> amend(context, checked) { old ->
            require(old is LumberjackTaskDefinition) { "work_area amendment requires a wood operation" }
            TaskChange.Replace(old.copy(area = WorkArea(WorkBox(block(context, "minimum"), block(context, "maximum")), old.area.exclusions)))
        } })))
        .then(Commands.literal("reserve").then(Commands.argument("amount", IntegerArgumentType.integer(0, 2304)).executes { context -> amend(context, checked) { old ->
            val amount = integer(context, "amount")
            TaskChange.Replace(when (old) {
                is TransportTaskDefinition -> old.copy(keepAtLeast = amount)
                is DeliveryTaskDefinition -> old.copy(keepAtLeast = amount)
                else -> throw IllegalArgumentException("reserve amendment requires cargo delivery")
            })
        } }))
        .then(Commands.literal("extend_time").then(Commands.argument("ticks", IntegerArgumentType.integer(1, 72000)).executes { context -> amend(context, checked) { TaskChange.ExtendTime(integer(context, "ticks")) } }))

    private fun amend(context: CommandContext<CommandSourceStack>, checked: Boolean, change: (TaskDefinition) -> TaskChange): Int = TaskCommands.withNpc(context) { player, npc ->
        val record = TaskStore.forServer(player.server).get(npc.npcUuid) ?: return@withNpc NpcActionResult.rejected("no assigned task", NpcActionCode.NOT_READY)
        try {
            val proposal = change(record.primary.definition)
            if (checked) {
                val expires = LongArgumentType.getLong(context, "expiresTick")
                TaskAmendments.request(player.server, npc, player, TaskAmendmentRequest(UuidArgument.getUuid(context, "taskId"),
                    UuidArgument.getUuid(context, "requestId"), player.uuid, integer(context, "revision"), expires - 1200, expires, proposal))
            } else TaskAmendments.automatic(player.server, npc, player, proposal)
        } catch (error: IllegalArgumentException) { NpcActionResult.rejected(error.message ?: "invalid amendment") }
    }
    private fun integer(context: CommandContext<CommandSourceStack>, name: String) = IntegerArgumentType.getInteger(context, name)
    private fun block(context: CommandContext<CommandSourceStack>, name: String): NpcBlockPosition {
        val p = BlockPosArgument.getBlockPos(context, name); return NpcBlockPosition(p.x, p.y, p.z)
    }
}
