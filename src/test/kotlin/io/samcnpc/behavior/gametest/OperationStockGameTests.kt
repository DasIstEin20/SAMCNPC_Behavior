package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object OperationStockGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 160, batch = "stock_authority")
    fun stockReadRequiresCurrentAuthorityAndReturnsOnlyActualVisibleCount(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level, "StockSam")
        val stranger = GameTestActor(helper.level, "StockOther")
        val arena = CombatGameTestArena(helper, actor.player)
        val server = helper.level.server
        arena.onReady { npc ->
            val at = helper.absolutePos(BlockPos(3, 1, 0))
            val query = NpcStockQuery(NpcBlockPosition(at.x, at.y, at.z), "minecraft:oak_log")
            val dimension = npc.snapshot().dimensionId
            try {
                helper.level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3)
                val chest = helper.level.getBlockEntity(at) as ChestBlockEntity
                chest.setItem(0, ItemStack(Items.OAK_LOG, 32))
                val original = chest.saveWithoutMetadata()
                val idle = OperationSupervisionApi.observe(server, actor.player, npc.npcUuid).observation
                val success = OperationStockApi.inspect(server, actor.player, npc.npcUuid, dimension, query)
                check(success.result.status == NpcActionStatus.SUCCEEDED)
                val value = success.stock as NpcStockRead.Observed
                check(value.count == 32 && value.slots == 27 && value.observedTick == helper.level.gameTime)
                check(chest.saveWithoutMetadata() == original)
                check(OperationSupervisionApi.observe(server, actor.player, npc.npcUuid).observation == idle)
                val denied = OperationStockApi.inspect(server, stranger.player, npc.npcUuid, dimension, query)
                check(denied.result.code == NpcActionCode.PERMISSION_DENIED && denied.stock == null)
                val wrongWorld = OperationStockApi.inspect(server, actor.player, npc.npcUuid, "minecraft:the_nether", query)
                check(wrongWorld.result.code == NpcActionCode.OUT_OF_RANGE && wrongWorld.stock == null)
                val offThread = CompletableFuture.supplyAsync {
                    OperationStockApi.inspect(server, actor.player, npc.npcUuid, dimension, query)
                }.get(3, TimeUnit.SECONDS)
                check(offThread.result.code == NpcActionCode.NOT_READY && offThread.stock == null)
                val position = actor.player.position()
                actor.player.teleportTo(helper.level, arena.start.x + 300, arena.start.y + 3, arena.start.z, 0F, 0F)
                val far = OperationStockApi.inspect(server, actor.player, npc.npcUuid, dimension, query)
                check(far.result.code == NpcActionCode.OUT_OF_RANGE && far.stock == null)
                actor.player.teleportTo(helper.level, position.x, position.y, position.z, 0F, 0F)
                chest.setItem(0, ItemStack(Items.OAK_LOG, 3))
                check((OperationStockApi.inspect(server, actor.player, npc.npcUuid, dimension, query).stock as NpcStockRead.Observed).count == 3)
                check(value.count == 32)
                helper.level.setBlock(at, Blocks.STONE.defaultBlockState(), 3)
                val unknown = OperationStockApi.inspect(server, actor.player, npc.npcUuid, dimension, query)
                check(unknown.result.status == NpcActionStatus.SUCCEEDED && unknown.stock is NpcStockRead.Unavailable)
                arena.close()
                val gone = OperationStockApi.inspect(server, actor.player, npc.npcUuid, dimension, query)
                check(gone.result.code == NpcActionCode.NOT_FOUND && gone.stock == null)
            } finally { arena.close(); actor.close(); stranger.close() }
            val disconnected = OperationStockApi.inspect(server, actor.player, npc.npcUuid, dimension, query)
            check(disconnected.result.code == NpcActionCode.PERMISSION_DENIED && disconnected.stock == null)
            helper.succeed()
        }
    }
}
