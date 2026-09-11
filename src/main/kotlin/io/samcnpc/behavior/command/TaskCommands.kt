package io.samcnpc.behavior.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.LumberjackTaskDefinition
import io.samcnpc.behavior.task.WorkArea
import io.samcnpc.behavior.task.WorkBox
import io.samcnpc.behavior.task.WoodSelection
import io.samcnpc.behavior.task.DeliveryTaskDefinition
import io.samcnpc.behavior.task.NavigateTaskDefinition
import io.samcnpc.behavior.task.TaskBudget
import io.samcnpc.behavior.task.TaskService
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcPosition
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

internal object TaskCommands {
    fun branch() = Commands.literal("task")
        .then(TaskCombatCommands.reactionBranch())
        .then(Commands.literal("status").then(Commands.argument("npc", StringArgumentType.word()).executes { context ->
            withNpc(context) { player, npc ->
                NpcActionResult.succeeded(TaskService.status(player.server, npc.npcUuid) ?: "NPC has no durable task")
            }
        }))
        .then(Commands.literal("pause").then(Commands.argument("npc", StringArgumentType.word()).executes { context ->
            withNpc(context) { player, npc -> TaskService.pause(player.server, npc.npcUuid) }
        }))
        .then(Commands.literal("resume").then(Commands.argument("npc", StringArgumentType.word()).executes { context ->
            withNpc(context) { player, npc -> TaskService.resume(player.server, npc.npcUuid) }
        }))
        .then(Commands.literal("cancel").then(Commands.argument("npc", StringArgumentType.word()).executes { context ->
            withNpc(context) { player, npc -> TaskService.cancel(player.server, npc.npcUuid) }
        }))
        .then(Commands.literal("assign").then(Commands.argument("npc", StringArgumentType.word())
            .then(lumberjackBranch())
            .then(TaskCombatCommands.attackBranch())
            .then(Commands.literal("navigate").then(Commands.argument("position", Vec3Argument.vec3())
                .executes { context -> assign(context, 6000) }
                .then(Commands.argument("durationTicks", IntegerArgumentType.integer(20, 72000))
                    .executes { context -> assign(context, IntegerArgumentType.getInteger(context, "durationTicks")) })
            ))
            .then(Commands.literal("deliver").then(Commands.argument("container", BlockPosArgument.blockPos())
                .then(Commands.argument("item", ResourceLocationArgument.id())
                    .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 2304))
                        .executes { context -> deliver(context, 0) }
                        .then(Commands.argument("keepAtLeast", IntegerArgumentType.integer(0, 2304))
                            .executes { context -> deliver(context, IntegerArgumentType.getInteger(context, "keepAtLeast")) })
                    )
                )
            ))))

    private fun lumberjackBranch() = Commands.literal("lumberjack")
        .then(Commands.argument("workMin", BlockPosArgument.blockPos())
            .then(Commands.argument("workMax", BlockPosArgument.blockPos())
                .then(Commands.argument("container", BlockPosArgument.blockPos())
                    .then(Commands.argument("wood", ResourceLocationArgument.id())
                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 2304))
                            .executes { context -> lumberjack(context, 6000, false) }
                            .then(exclusionBranch(6000))
                            .then(Commands.argument("durationTicks", IntegerArgumentType.integer(20, 72000))
                                .executes { context -> lumberjack(context, IntegerArgumentType.getInteger(context, "durationTicks"), false) }
                                .then(exclusionBranch(null)))
                        )
                    )
                )
            )
        )

    private fun exclusionBranch(duration: Int?) = Commands.literal("exclude")
        .then(Commands.argument("excludeMin", BlockPosArgument.blockPos())
            .then(Commands.argument("excludeMax", BlockPosArgument.blockPos()).executes { context ->
                lumberjack(context, duration ?: IntegerArgumentType.getInteger(context, "durationTicks"), true)
            }))

    private fun lumberjack(context: CommandContext<CommandSourceStack>, duration: Int, exclude: Boolean): Int = withNpc(context) { player, npc ->
        val area = WorkArea(WorkBox(block(context, "workMin"), block(context, "workMax")),
            if (exclude) listOf(WorkBox(block(context, "excludeMin"), block(context, "excludeMax"))) else emptyList())
        val definition = LumberjackTaskDefinition(npc.snapshot().dimensionId, area,
            WoodSelection(listOf(ResourceLocationArgument.getId(context, "wood").toString())), block(context, "container"),
            IntegerArgumentType.getInteger(context, "quantity"), budget = TaskBudget(ticks = duration))
        TaskService.assign(player.server, npc, definition)
    }

    private fun block(context: CommandContext<CommandSourceStack>, name: String): NpcBlockPosition {
        val position = BlockPosArgument.getBlockPos(context, name)
        return NpcBlockPosition(position.x, position.y, position.z)
    }

    private fun assign(context: CommandContext<CommandSourceStack>, durationTicks: Int): Int = withNpc(context) { player, npc ->
        val position = Vec3Argument.getVec3(context, "position")
        val definition = NavigateTaskDefinition(npc.snapshot().dimensionId,
            NpcPosition(position.x, position.y, position.z), budget = TaskBudget(ticks = durationTicks))
        TaskService.assign(player.server, npc, definition)
    }

    private fun deliver(context: CommandContext<CommandSourceStack>, keepAtLeast: Int): Int = withNpc(context) { player, npc ->
        val position = BlockPosArgument.getBlockPos(context, "container")
        TaskService.assign(player.server, npc, DeliveryTaskDefinition(npc.snapshot().dimensionId,
            NpcBlockPosition(position.x, position.y, position.z), ResourceLocationArgument.getId(context, "item").toString(),
            IntegerArgumentType.getInteger(context, "quantity"), keepAtLeast))
    }

    internal fun withNpc(context: CommandContext<CommandSourceStack>, action: (ServerPlayer, NpcFacade) -> NpcActionResult): Int {
        val player = context.source.playerOrException
        val handle = BehaviorCommands.findNpc(player, StringArgumentType.getString(context, "npc"))
        if (handle == null) {
            context.source.sendFailure(Component.literal("No unambiguous loaded NPC matches that name/UUID within 256 blocks."))
            return 0
        }
        if (!BehaviorCommands.canControl(player, handle)) {
            context.source.sendFailure(Component.literal("Only the summoner or an operator can control or inspect this task."))
            return 0
        }
        val npc = CoreNpcApi.service(player.server).runtime(handle)
        if (npc == null) {
            context.source.sendFailure(Component.literal("NPC is no longer loaded."))
            return 0
        }
        val result = action(player, npc)
        if (result.status != NpcActionStatus.SUCCEEDED) {
            context.source.sendFailure(Component.literal("${result.code}: ${result.detail}"))
            return 0
        }
        context.source.sendSuccess({ Component.literal(result.detail) }, false)
        return Command.SINGLE_SUCCESS
    }
}
