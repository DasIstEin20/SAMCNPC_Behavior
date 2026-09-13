package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.*
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object TaskAmendmentGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2800, batch = "cargo_amendments")
    fun realTransfersRevisionReplayPermissionsRedirectAndNewResourcePreserveExactAccounting(helper: GameTestHelper) {
        val server = helper.level.server
        val actor = GameTestActor(helper.level, "AmendCargo")
        val stranger = GameTestActor(helper.level, "OtherAmend")
        val arena = CombatGameTestArena(helper, actor.player)
        stranger.player.teleportTo(helper.level, arena.start.x + 1, arena.start.y + 3, arena.start.z + 8, 0.0F, 0.0F)
        val source = chest(helper, 2, 3); val first = chest(helper, 14, -3); val second = chest(helper, 18, 3)
        source.setItem(0, ItemStack(Items.OAK_LOG, 64)); source.setItem(1, ItemStack(Items.BIRCH_LOG, 64))
        for (slot in 0 until 26) first.setItem(slot, ItemStack(Items.STONE, 64))
        first.setItem(26, ItemStack(Items.OAK_LOG, 60))
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.OAK_LOG, 5)); arena.give(npc, ItemStack(Items.BIRCH_LOG, 3))
            arena.assign(npc, TransportTaskDefinition(npc.snapshot().dimensionId, ContainerChoices(listOf(pos(source))), ContainerChoices(listOf(pos(first))),
                "minecraft:oak_log", 16, arena.start, keepAtLeast = 5, returnTo = arena.start, budget = TaskBudget(ticks = 2700)))
        }
        var amended = false; var switched = false; var firstRemaining = 0
        var taskId: UUID? = null; var frameId: UUID? = null
        arena.observe { npc, record ->
            val ledger = checkNotNull(record.primary.transport).ledger
            if (!amended && ledger.delivered == 4) {
                taskId = record.id; frameId = record.primary.id
                check(TaskService.pause(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                firstRemaining = record.primary.remainingTicks
                val now = npc.snapshot().gameTime
                val add = TaskAmendmentRequest(record.id, UUID.randomUUID(), actor.player.uuid, 0, now, now + 1200, TaskChange.Quantity(8, QuantityChangeMode.ADD))
                check(publicRequest(server, npc, actor.player, add).status == NpcActionStatus.SUCCEEDED)
                val after = TaskCodec.write(record)
                check(publicRequest(server, npc, actor.player, add).status == NpcActionStatus.SUCCEEDED)
                check(TaskCodec.write(record) == after) { "duplicate addition repeated an effect" }
                check(publicRequest(server, npc, actor.player, add.copy(requestId = UUID.randomUUID())).code == NpcActionCode.CONFLICT)
                check(publicRequest(server, npc, stranger.player, add.copy(requestId = UUID.randomUUID(), actorUuid = stranger.player.uuid, expectedRevision = 1)).code == NpcActionCode.PERMISSION_DENIED)
                check(TaskCodec.write(record) == after) { "stale/unauthorized request mutated task" }
                val redirect = add.copy(requestId = UUID.randomUUID(), expectedRevision = 1, change = TaskChange.Redirect(ContainerChoices(listOf(pos(second)))))
                check(publicRequest(server, npc, actor.player, redirect).status == NpcActionStatus.SUCCEEDED)
                reload(server)
                val loaded = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
                check(loaded.id == taskId && loaded.primary.id == frameId && loaded.primary.remainingTicks == firstRemaining)
                check(publicRequest(server, npc, actor.player, add).status == NpcActionStatus.SUCCEEDED)
                check(loaded.amendments.revision == 2 && (loaded.primary.definition as TransportTaskDefinition).quantity == 24)
                check(TaskService.resume(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                amended = true
            }
            if (amended && !switched && ledger.delivered == 24) {
                check(count(first, Items.OAK_LOG) == 64 && count(second, Items.OAK_LOG) == 20 && count(source, Items.OAK_LOG) == 40)
                val definition = record.primary.definition as TransportTaskDefinition
                val order = OperationOrder.Transport(definition.dimensionId,
                    OperationContainers(definition.sources.positions), OperationContainers(definition.destinations.positions),
                    "minecraft:birch_log", 10, definition.anchor, definition.travelRadius, 3, definition.sourceKeepAtLeast,
                    definition.returnTo, OperationBudget(definition.budget.ticks, definition.budget.attempts, definition.budget.backoffTicks))
                val now = npc.snapshot().gameTime
                val reply = OperationSupervisionApi.amend(server, actor.player, npc.npcUuid,
                    OperationAmendmentRequest(record.id, UUID.randomUUID(), record.amendments.revision, now, now + 1200,
                        OperationChange.Replace(order, OperationObjectiveMode.NEW_OBJECTIVE)))
                check(reply.result.status == NpcActionStatus.SUCCEEDED && reply.amendment?.outcome == OperationAmendmentOutcome.APPLIED) { reply.result.detail }
                val old = record.amendments.objectives.single()
                check(old.confirmed == 24 && old.deliveries == mapOf(pos(first) to mapOf("minecraft:oak_log" to 4), pos(second) to mapOf("minecraft:oak_log" to 20)))
                check(record.primary.transport?.ledger?.delivered == 0 && record.primary.transport?.ledger?.initial == 3)
                check(record.primary.remainingTicks <= firstRemaining && record.id == taskId && record.primary.id == frameId)
                reload(server); switched = true
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && switched && record.amendments.revision == 3) { TaskService.status(server, npc.npcUuid).orEmpty() }
                check(count(first, Items.OAK_LOG) == 64 && count(second, Items.OAK_LOG) == 20 && count(second, Items.BIRCH_LOG) == 10)
                check(count(source, Items.OAK_LOG) == 40 && count(source, Items.BIRCH_LOG) == 54)
                check(TaskDelivery.inventoryCount(npc, "minecraft:oak_log") == 5 && TaskDelivery.inventoryCount(npc, "minecraft:birch_log") == 3)
                check(record.amendments.objectives.single().confirmed == 24 && record.primary.transport?.ledger?.delivered == 10)
                check(TaskNavigator.distanceSquared(npc.snapshot().position, arena.start) <= 0.75 * 0.75)
                arena.succeed(npc, record); actor.close(); stranger.close()
            }
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2800, batch = "wood_pending_amendment")
    fun outputChangeWaitsForPhysicalScaffoldCleanupAndSurvivesPausedReload(helper: GameTestHelper) {
        val server = helper.level.server
        val actor = GameTestActor(helper.level, "AmendWood")
        val arena = CombatGameTestArena(helper, actor.player)
        val supplies = chest(helper, 2, 3); val first = chest(helper, 14, -3); val second = chest(helper, 18, 3)
        supplies.setItem(0, ItemStack(Items.IRON_AXE)); supplies.setItem(1, ItemStack(Items.IRON_SHOVEL)); supplies.setItem(2, ItemStack(Items.COARSE_DIRT, 12))
        for (y in 1..9) helper.setBlock(BlockPos(9, y, 0), Blocks.OAK_LOG)
        val wood = WoodSelection(listOf("samcnpc:oak"))
        arena.onReady { npc -> arena.assign(npc, LumberjackTaskDefinition(npc.snapshot().dimensionId,
            WorkArea(WorkBox(absolute(helper, 7, 1, -2), absolute(helper, 11, 12, 2))), wood, pos(first), 9,
            budget = TaskBudget(ticks = 2700), version = 2, supplySources = ContainerChoices(listOf(pos(supplies))))) }
        var queued = false; var applied = false; var request: TaskAmendmentRequest? = null; var budgetAtRequest = 0
        var expiring: TaskAmendmentRequest? = null
        var expiryVerified = false
        arena.observe { npc, record ->
            val state = checkNotNull(record.primary.lumberjack)
            if (expiring == null && state.job.pillarSession?.placedPositions?.isNotEmpty() == true) {
                val now = npc.snapshot().gameTime
                val short = TaskAmendmentRequest(record.id, UUID.randomUUID(), actor.player.uuid, 0, now, now + 1,
                    TaskChange.Redirect(ContainerChoices(listOf(pos(second)))))
                val reply = publicReply(server, npc, actor.player, short)
                check(reply.result.status == NpcActionStatus.SUCCEEDED && reply.amendment?.outcome == OperationAmendmentOutcome.PENDING)
                expiring = short
            }
            val expiredRequest = expiring
            if (!expiryVerified && expiredRequest != null && record.amendments.pending == null) {
                val replay = publicReply(server, npc, actor.player, expiredRequest)
                check(replay.result.status == NpcActionStatus.SUCCEEDED && replay.amendment?.outcome == OperationAmendmentOutcome.EXPIRED)
                check(record.amendments.revision == 0 && (record.primary.definition as LumberjackTaskDefinition).destination == pos(first))
                expiryVerified = true
            }
            if (!queued && expiryVerified && state.job.pillarSession?.placedPositions?.isNotEmpty() == true) {
                val now = npc.snapshot().gameTime
                val change = TaskAmendmentRequest(record.id, UUID.randomUUID(), actor.player.uuid, 0, now, now + 1200, TaskChange.Redirect(ContainerChoices(listOf(pos(second)))))
                request = change; budgetAtRequest = record.primary.remainingTicks
                val result = publicRequest(server, npc, actor.player, change)
                check(result.status == NpcActionStatus.SUCCEEDED && result.detail.startsWith("PENDING")) { result.detail }
                check(record.amendments.revision == 0 && (record.primary.definition as LumberjackTaskDefinition).destination == pos(first))
                com.mojang.logging.LogUtils.getLogger().info("WOOD_RELOAD_PROOF before live={} checkpoint={} loadGeneration={} loadInventory={}",
                    HarvestResources.inventoryCounts(npc), state.resources.retained(), npc.inventoryLoadSnapshot()?.generation,
                    npc.inventoryLoadSnapshot()?.inventory?.filter { !it.isEmpty })
                check(TaskService.pause(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                reload(server)
                com.mojang.logging.LogUtils.getLogger().info("WOOD_RELOAD_PROOF after live={} checkpoint={} loadGeneration={} loadInventory={}",
                    HarvestResources.inventoryCounts(npc), TaskStore.forServer(server).get(npc.npcUuid)?.primary?.lumberjack?.resources?.retained(),
                    npc.inventoryLoadSnapshot()?.generation, npc.inventoryLoadSnapshot()?.inventory?.filter { !it.isEmpty })
                val loaded = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
                check(loaded.amendments.pending == change && loaded.primary.remainingTicks == budgetAtRequest && loaded.amendments.revision == 0)
                check(publicRequest(server, npc, actor.player, change).detail.startsWith("PENDING"))
                check(TaskService.resume(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                queued = true
            }
            if (queued && record.amendments.revision == 1 && !applied) {
                check(state.job.pillarSession?.placedPositions.orEmpty().isEmpty()) { "queued redirect applied before physical cleanup" }
                check(record.primary.remainingTicks < budgetAtRequest && record.amendments.pending == null)
                check(publicRequest(server, npc, actor.player, checkNotNull(request)).detail.startsWith("APPLIED"))
                applied = true
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && queued && applied && expiryVerified) { TaskService.status(server, npc.npcUuid).orEmpty() }
                check(count(first, Items.OAK_LOG) == 0 && count(second, Items.OAK_LOG) == 9 && state.resources.delivered(wood) == 9)
                check(state.deliveries[pos(second)]?.get("minecraft:oak_log") == 9)
                check(state.job.pillarSession?.placedPositions.orEmpty().isEmpty())
                for (x in 7..11) for (z in -2..2) for (y in 1..12) check(helper.getBlockState(BlockPos(x, y, z)).isAir) { "wood or scaffold remained after amended task" }
                arena.succeed(npc, record); actor.close()
            }
        }
    }
    private fun publicRequest(server: net.minecraft.server.MinecraftServer, npc: NpcFacade,
        actor: net.minecraft.server.level.ServerPlayer, request: TaskAmendmentRequest): NpcActionResult =
        publicReply(server, npc, actor, request).result

    private fun publicReply(server: net.minecraft.server.MinecraftServer, npc: NpcFacade,
        actor: net.minecraft.server.level.ServerPlayer, request: TaskAmendmentRequest): OperationReply {
        check(request.actorUuid == actor.uuid)
        val change = when (val original = request.change) {
            is TaskChange.Quantity -> OperationChange.Quantity(original.amount,
                if (original.mode == QuantityChangeMode.ADD) OperationQuantityMode.ADD else OperationQuantityMode.TOTAL)
            is TaskChange.Redirect -> OperationChange.Recipients(OperationContainers(original.recipients.positions,
                if (original.recipients.preference == ContainerPreference.NEAREST) OperationContainerPreference.NEAREST else OperationContainerPreference.ORDERED))
            else -> error("this physical fixture uses quantity and recipient amendments")
        }
        val reply = OperationSupervisionApi.amend(server, actor, npc.npcUuid, OperationAmendmentRequest(
            request.taskId, request.requestId, request.expectedRevision, request.issuedTick, request.expiresTick, change))
        if (reply.result.status == NpcActionStatus.SUCCEEDED) {
            val receipt = checkNotNull(reply.amendment)
            check(receipt.requestId == request.requestId && receipt.taskId == request.taskId)
            check(receipt.revision == request.expectedRevision + if (receipt.outcome == OperationAmendmentOutcome.APPLIED) 1 else 0)
            check(reply.observation?.task?.taskId == request.taskId)
        } else check(reply.amendment == null)
        return reply
    }
    private fun reload(server: net.minecraft.server.MinecraftServer) {
        val saved = TaskStore.forServer(server).save(CompoundTag()); check(BehaviorRuntimeService.reload().accepted)
        server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(saved))
    }
    private fun chest(helper: GameTestHelper, x: Int, z: Int): ChestBlockEntity {
        val relative = BlockPos(x, 1, z); helper.setBlock(relative, Blocks.CHEST)
        return helper.level.getBlockEntity(helper.absolutePos(relative)) as ChestBlockEntity
    }
    private fun pos(chest: ChestBlockEntity) = NpcBlockPosition(chest.blockPos.x, chest.blockPos.y, chest.blockPos.z)
    private fun count(chest: ChestBlockEntity, item: Item): Int = (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(item)) chest.getItem(it).count else 0 }
    private fun absolute(helper: GameTestHelper, x: Int, y: Int, z: Int): NpcBlockPosition {
        val p = helper.absolutePos(BlockPos(x, y, z)); return NpcBlockPosition(p.x, p.y, p.z)
    }
}
