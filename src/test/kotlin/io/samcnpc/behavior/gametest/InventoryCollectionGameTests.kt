package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object InventoryCollectionGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1200, batch = "inventory_collection")
    fun collectsCapturedGearAfterPauseAndStoreReloadWithoutTakingLaterStock(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level, "CollectGear")
        val stranger = GameTestActor(helper.level, "CollectOther")
        val arena = CombatGameTestArena(helper, actor.player)
        val server = helper.level.server
        val chest = chest(helper)
        val gear = linkedMapOf(Items.IRON_AXE to 1, Items.IRON_SHOVEL to 1, Items.IRON_PICKAXE to 1,
            Items.IRON_SWORD to 1, Items.BOW to 1, Items.ARROW to 64, Items.IRON_HOE to 1,
            Items.DIRT to 64, Items.IRON_HELMET to 1, Items.IRON_CHESTPLATE to 1,
            Items.IRON_LEGGINGS to 1, Items.IRON_BOOTS to 1, Items.WHEAT_SEEDS to 16)
        for ((index, entry) in gear.entries.withIndex()) chest.setItem(index, ItemStack(entry.key, entry.value))
        var initial: OperationAssignmentRequest? = null
        var reloaded = false
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.DIRT, 3))
            val now = npc.snapshot().gameTime
            val request = OperationAssignmentRequest(null, now, now + 1200,
                OperationInventoryOrder(npc.snapshot().dimensionId, OperationInventoryWork.Collect(position(chest)), arena.start,
                    workTicks = 1000, budget = OperationBudget(ticks = 1100)))
            initial = request
            check(OperationSupervisionApi.assign(server, stranger.player, npc.npcUuid, request).result.code == NpcActionCode.PERMISSION_DENIED)
            check(TaskStore.forServer(server).get(npc.npcUuid) == null)
            check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, request).result.status == NpcActionStatus.SUCCEEDED)
            val state = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid)?.primary?.inventory)
            check(state.goals == gear.mapKeys { id(it.key) })
            // These arrivals are outside the immutable assignment quota, including the same item ID.
            chest.setItem(20, ItemStack(Items.DIAMOND, 3))
            chest.setItem(21, ItemStack(Items.DIRT, 1))
        }
        arena.observe { npc, record ->
            check(record.status != TaskStatus.FAILED) { record.report() }
            val state = checkNotNull(record.primary.inventory)
            if (!reloaded && state.resources.entries.values.any { it.supplied > 0 }) {
                check(TaskService.pause(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                val before = TaskCodec.write(record)
                val saved = TaskStore.forServer(server).save(CompoundTag())
                server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(saved))
                val loaded = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
                check(TaskCodec.write(loaded) == before)
                check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, checkNotNull(initial)).result.code == NpcActionCode.CONFLICT)
                check(TaskService.resume(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                reloaded = true
                return@observe
            }
            if (record.status == TaskStatus.COMPLETED) {
                check(reloaded && record.primary.definition.version == 2)
                for ((item, quantity) in gear) {
                    val original = if (item == Items.DIRT) 3 else 0
                    check(TaskDelivery.inventoryCount(npc, id(item)) == quantity + original) { "wrong carried count for ${id(item)}" }
                    check(state.resources.entries[id(item)]?.supplied == quantity)
                }
                check(count(chest, Items.DIAMOND) == 3 && count(chest, Items.DIRT) == 1)
                check((0 until chest.containerSize).sumOf { chest.getItem(it).count } == 4)
                val before = TaskCodec.write(record)
                check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, checkNotNull(initial)).result.code == NpcActionCode.CONFLICT)
                check(TaskCodec.write(record) == before)
                check(TaskNavigator.distanceSquared(npc.snapshot().position, arena.start) <= 1.0)
                arena.succeed(npc, record); actor.close(); stranger.close()
            }
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 300, batch = "inventory_collection_reject")
    fun oversizedSourceCannotReplaceAnExistingCompletedTask(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level, "CollectLimit")
        val arena = CombatGameTestArena(helper, actor.player)
        val server = helper.level.server
        val chest = chest(helper)
        chest.setItem(0, ItemStack(Items.DIRT, 64))
        arena.onReady { npc ->
            val now = npc.snapshot().gameTime
            val request = OperationAssignmentRequest(null, now, now + 200,
                OperationOrder.Navigate(npc.snapshot().dimensionId, arena.start))
            check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, request).result.status == NpcActionStatus.SUCCEEDED)
        }
        arena.observe { npc, record ->
            if (!record.status.terminal) return@observe
            check(record.status == TaskStatus.COMPLETED)
            val before = TaskCodec.write(record)
            val inventoryBefore = npc.inventoryContents()
            val now = npc.snapshot().gameTime
            val request = OperationAssignmentRequest(record.id, now, now + 100,
                OperationInventoryOrder(npc.snapshot().dimensionId, OperationInventoryWork.Collect(position(chest), 63), arena.start))
            val result = OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, request)
            check(result.result.code == NpcActionCode.NOT_READY && result.result.detail == "COLLECTION_ITEM_LIMIT")
            check(TaskCodec.write(checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))) == before)
            check(npc.inventoryContents() == inventoryBefore && count(chest, Items.DIRT) == 64)
            arena.succeed(npc, record); actor.close()
        }
    }

    private fun chest(helper: GameTestHelper): ChestBlockEntity {
        val relative = BlockPos(2, 1, 3)
        helper.setBlock(relative, Blocks.CHEST)
        return helper.level.getBlockEntity(helper.absolutePos(relative)) as ChestBlockEntity
    }
    private fun position(chest: ChestBlockEntity) = NpcBlockPosition(chest.blockPos.x, chest.blockPos.y, chest.blockPos.z)
    private fun id(item: Item) = checkNotNull(ForgeRegistries.ITEMS.getKey(item)).toString()
    private fun count(chest: ChestBlockEntity, item: Item) = (0 until chest.containerSize).sumOf {
        val stack = chest.getItem(it); if (stack.item == item) stack.count else 0
    }
}
