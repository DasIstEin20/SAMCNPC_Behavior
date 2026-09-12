package io.samcnpc.behavior.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcHandle
import io.samcnpc.core.api.NpcLoadedQuery
import io.samcnpc.core.api.NpcPosition
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID

object BehaviorCommands {
    @SubscribeEvent
    fun register(event: RegisterCommandsEvent) {
        val branch = Commands.literal("behavior")
            .then(Commands.literal("planning_budget").requires { it.hasPermission(2) }.executes { context ->
                context.source.sendSuccess({ Component.literal(io.samcnpc.behavior.runtime.BehaviorPlanning.statistics().toString()) }, false)
                1
            })
            .then(TaskCommands.branch())
            .then(Commands.literal("reload").requires { it.hasPermission(2) }.executes { context ->
                val report = BehaviorRuntimeService.reload()
                if (report.accepted) {
                    context.source.sendSuccess(
                        { Component.literal("SAMCNPC behavior reload activated ${BehaviorRuntimeService.activePackIds().size} pack(s) from ${BehaviorRuntimeService.externalDirectory()}.") },
                        true,
                    )
                    Command.SINGLE_SUCCESS
                } else {
                    context.source.sendFailure(Component.literal("SAMCNPC behavior reload rejected; last-known-good registry remains active: ${report.messages.joinToString(" | ")}"))
                    0
                }
            })
            .then(Commands.literal("packs").executes { context ->
                context.source.sendSuccess({ Component.literal("Active behavior packs: ${BehaviorRuntimeService.activePackIds().ifEmpty { listOf("<none>") }.joinToString(", ")}") }, false)
                Command.SINGLE_SUCCESS
            })
            .then(
                Commands.literal("diagnostics")
                    .then(Commands.argument("npc", StringArgumentType.word()).executes { context ->
                        val player = context.source.playerOrException
                        val npc = findNpc(player, StringArgumentType.getString(context, "npc")) ?: run {
                            context.source.sendFailure(Component.literal("No visible NPC matches that UUID."))
                            return@executes 0
                        }
                        if (!canControl(player, npc)) {
                            context.source.sendFailure(Component.literal("Only the summoner or an operator can inspect this NPC."))
                            return@executes 0
                        }
                        val diagnostic = BehaviorRuntimeService.diagnostic(npc.npcUuid)
                        context.source.sendSuccess(
                            {
                                Component.literal(
                                    if (diagnostic == null) "No behavior tick has run for ${npc.npcUuid}."
                                    else "packs=${diagnostic.activePacks.joinToString(", ")}; intents=${diagnostic.selectedIntents.joinToString(", ")}; " +
                                        "problem=${diagnostic.lastProblem.orEmpty()}; tick=${diagnostic.gameTime}; " +
                                        "decisions=${diagnostic.work.decisions}; ruleEvaluations=${diagnostic.work.evaluatedRules}; " +
                                        "intents=${diagnostic.work.executedIntents}/${diagnostic.work.eligibleIntents}; " +
                                        "queries=${diagnostic.work.lastObservations}; decisionNanos=${diagnostic.work.lastDecisionNanos ?: "profiling disabled"}; " +
                                        "task=${diagnostic.taskStatus ?: "idle"}; combat=${diagnostic.combatStatus ?: "idle"}",
                                )
                            },
                            false,
                        )
                        Command.SINGLE_SUCCESS
                    }),
            )
            .then(
                Commands.literal("assign")
                    .then(Commands.argument("npc", StringArgumentType.word())
                        .then(Commands.argument("packs", StringArgumentType.greedyString()).executes { context ->
                            val player = context.source.playerOrException
                            val npc = findNpc(player, StringArgumentType.getString(context, "npc")) ?: run {
                                context.source.sendFailure(Component.literal("No visible NPC matches that UUID."))
                                return@executes 0
                            }
                            if (!canControl(player, npc)) {
                                context.source.sendFailure(Component.literal("Only the summoner or an operator can assign packs."))
                                return@executes 0
                            }
                            val ids = StringArgumentType.getString(context, "packs").split(',').map(String::trim).filter(String::isNotEmpty)
                            val unknown = ids.filter { it !in BehaviorRuntimeService.activePackIds() }
                            if (unknown.isNotEmpty()) {
                                context.source.sendFailure(Component.literal("Unknown active pack(s): ${unknown.joinToString(", ")}"))
                                return@executes 0
                            }
                            val result = BehaviorRuntimeService.assignPacks(player.server, npc.npcUuid, ids)
                            if (result.status == io.samcnpc.core.api.NpcActionStatus.SUCCEEDED) {
                                context.source.sendSuccess({ Component.literal(result.detail) }, true)
                                Command.SINGLE_SUCCESS
                            } else {
                                context.source.sendFailure(Component.literal(result.detail))
                                0
                            }
                        }),
                    ),
            )
            .then(
                Commands.literal("lumberjack")
                    .then(
                        Commands.literal("status")
                            .then(Commands.argument("npc", StringArgumentType.word()).executes { context ->
                                val player = context.source.playerOrException
                                val npc = findNpc(player, StringArgumentType.getString(context, "npc")) ?: run {
                                    context.source.sendFailure(Component.literal("No visible NPC matches that UUID."))
                                    return@executes 0
                                }
                                if (!canControl(player, npc)) {
                                    context.source.sendFailure(Component.literal("Only the summoner or an operator can inspect this demo."))
                                    return@executes 0
                                }
                                val status = LumberjackService.status(player.server, npc.npcUuid)
                                context.source.sendSuccess(
                                    { Component.literal(status ?: "This NPC has no active lumberjack demo job.") },
                                    false,
                                )
                                Command.SINGLE_SUCCESS
                            }),
                    )
                    .then(
                        Commands.literal("cancel")
                            .then(Commands.argument("npc", StringArgumentType.word()).executes { context ->
                                val player = context.source.playerOrException
                                val npc = findNpc(player, StringArgumentType.getString(context, "npc")) ?: run {
                                    context.source.sendFailure(Component.literal("No visible NPC matches that UUID."))
                                    return@executes 0
                                }
                                if (!canControl(player, npc)) {
                                    context.source.sendFailure(Component.literal("Only the summoner or an operator can cancel this demo."))
                                    return@executes 0
                                }
                                val runtime = CoreNpcApi.service(player.server).runtime(npc) ?: run {
                                    context.source.sendFailure(Component.literal("NPC is no longer loaded."))
                                    return@executes 0
                                }
                                val result = LumberjackService.cancel(player.server, runtime)
                                if (result.status == io.samcnpc.core.api.NpcActionStatus.SUCCEEDED) {
                                    context.source.sendSuccess({ Component.literal(result.detail) }, true)
                                    Command.SINGLE_SUCCESS
                                } else {
                                    context.source.sendFailure(Component.literal(result.detail))
                                    0
                                }
                            }),
                    )
                    .then(Commands.argument("npc", StringArgumentType.word()).executes { context ->
                        val player = context.source.playerOrException
                        val npc = findNpc(player, StringArgumentType.getString(context, "npc")) ?: run {
                            context.source.sendFailure(Component.literal("No visible NPC matches that UUID."))
                            return@executes 0
                        }
                        if (!canControl(player, npc)) {
                            context.source.sendFailure(Component.literal("Only the summoner or an operator can start this demo."))
                            return@executes 0
                        }
                        val runtime = CoreNpcApi.service(player.server).runtime(npc) ?: run {
                            context.source.sendFailure(Component.literal("NPC is no longer loaded."))
                            return@executes 0
                        }
                        val result = LumberjackService.start(player.server, runtime, runtime.worldView())
                        if (result.status == io.samcnpc.core.api.NpcActionStatus.SUCCEEDED) {
                            context.source.sendSuccess({ Component.literal(result.detail) }, true)
                            Command.SINGLE_SUCCESS
                        } else {
                            context.source.sendFailure(Component.literal(result.detail))
                            0
                        }
                    }),
            )
        val root = event.dispatcher.root.getChild("samcnpc") as? LiteralCommandNode<CommandSourceStack>
        if (root != null) {
            root.addChild(branch.build())
        } else {
            event.dispatcher.register(Commands.literal("samcnpc").then(branch))
        }
    }

    internal fun findNpc(player: ServerPlayer, rawId: String): NpcHandle? {
        val candidates = CoreNpcApi.service(player.server).loadedNearby(
            NpcLoadedQuery(
                dimensionId = player.serverLevel().dimension().location().toString(),
                center = NpcPosition(player.x, player.y, player.z),
                radius = SEARCH_RADIUS,
            ),
        )
        val exactId = candidates.firstOrNull { it.npcUuid.toString().equals(rawId, ignoreCase = true) }
        if (exactId != null) {
            return exactId
        }
        val exactName = candidates.filter { it.displayName.equals(rawId, ignoreCase = true) }
        val namePrefix = candidates.filter { it.displayName.startsWith(rawId, ignoreCase = true) }
        val uuidPrefix = candidates.filter { it.npcUuid.toString().startsWith(rawId, ignoreCase = true) }
        return when {
            exactName.size == 1 -> exactName.single()
            exactName.size > 1 -> null
            namePrefix.size == 1 -> namePrefix.single()
            namePrefix.size > 1 -> null
            uuidPrefix.size == 1 -> uuidPrefix.single()
            else -> null
        }
    }

    internal fun canControl(player: ServerPlayer, handle: NpcHandle): Boolean =
        player.hasPermissions(2) || CoreNpcApi.service(player.server).runtime(handle)?.snapshot()?.summonerUuid == player.uuid

    private const val SEARCH_RADIUS = 256.0
}
