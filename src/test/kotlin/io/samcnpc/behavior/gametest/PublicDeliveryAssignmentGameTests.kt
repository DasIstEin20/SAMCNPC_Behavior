package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object PublicDeliveryAssignmentGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1200, batch = "public_assignment_delivery")
    fun publicDeliveryMovesExactCarriedStockAndCompletedRequestCannotRepeatAfterStoreReload(helper: GameTestHelper) {
        val server = helper.level.server
        val actor = GameTestActor(helper.level, "PublicDelivery")
        val arena = CombatGameTestArena(helper, actor.player)
        val relative = BlockPos(14, 1, -3)
        helper.setBlock(relative, Blocks.CHEST)
        val chest = helper.level.getBlockEntity(helper.absolutePos(relative)) as ChestBlockEntity
        chest.setItem(0, ItemStack(Items.OAK_LOG, 2))
        var initial: OperationAssignmentRequest? = null
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.OAK_LOG, 10))
            arena.give(npc, ItemStack(Items.DIAMOND, 3))
            val now = npc.snapshot().gameTime
            val order = OperationOrder.Deliver(npc.snapshot().dimensionId,
                NpcBlockPosition(chest.blockPos.x, chest.blockPos.y, chest.blockPos.z),
                "minecraft:oak_log", 6, arena.start, keepAtLeast = 4, budget = OperationBudget(ticks = 900))
            val request = OperationAssignmentRequest(null, now, now + 1200, order)
            initial = request
            check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, request).result.status == NpcActionStatus.SUCCEEDED)
            check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, request).result.code == NpcActionCode.CONFLICT)
        }
        arena.observe { npc, record ->
            if (!record.status.terminal) return@observe
            check(record.status == TaskStatus.COMPLETED && record.primary.definition.version == 2) { record.report() }
            val count = (0 until chest.containerSize).sumOf { slot ->
                val item = chest.getItem(slot); if (item.item == Items.OAK_LOG) item.count else 0
            }
            check(count == 8 && TaskDelivery.inventoryCount(npc, "minecraft:oak_log") == 4)
            check(TaskDelivery.inventoryCount(npc, "minecraft:diamond") == 3)
            check(record.primary.remainingTicks < 900 && record.totalFailures == 0)
            val before = TaskCodec.write(record)
            val saved = TaskStore.forServer(server).save(CompoundTag())
            server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(saved))
            val request = checkNotNull(initial)
            val replay = OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, request)
            check(replay.result.code == NpcActionCode.CONFLICT && replay.observation?.task?.taskId == record.id)
            check(replay.observation?.task?.state == OperationTaskState.COMPLETED)
            val loaded = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
            check(TaskCodec.write(loaded) == before)
            val changed = request.copy(order = (request.order as OperationOrder.Deliver).copy(quantity = 1))
            check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, changed).result.code == NpcActionCode.CONFLICT)
            check(TaskCodec.write(loaded) == before && TaskDelivery.inventoryCount(npc, "minecraft:oak_log") == 4)
            arena.succeed(npc, loaded); actor.close()
        }
    }
}
