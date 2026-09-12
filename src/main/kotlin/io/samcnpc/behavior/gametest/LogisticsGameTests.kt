package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object LogisticsGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2400, batch = "transport_conservation")
    fun realPartialTransfersAlternativesReservesExternalChangesReloadAndReturnConserveCargo(helper: GameTestHelper) {
        val server = helper.level.server
        val arena = CombatGameTestArena(helper)
        val sourceA = chest(helper, 2, -3); val sourceB = chest(helper, 2, 3)
        val recipientA = chest(helper, 16, -3); val recipientB = chest(helper, 16, 3)
        sourceA.setItem(0, ItemStack(Items.OAK_LOG, 6)); sourceB.setItem(0, ItemStack(Items.OAK_LOG, 64)); sourceB.setItem(1, ItemStack(Items.OAK_LOG, 6))
        for (slot in 0 until 26) recipientA.setItem(slot, ItemStack(Items.STONE, 64))
        recipientA.setItem(26, ItemStack(Items.OAK_LOG, 60))
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.OAK_LOG, 5))
            arena.assign(npc, TransportTaskDefinition(npc.snapshot().dimensionId, ContainerChoices(listOf(pos(sourceA), pos(sourceB))),
                ContainerChoices(listOf(pos(recipientA), pos(recipientB))), "minecraft:oak_log", 24, arena.start,
                keepAtLeast = 5, sourceKeepAtLeast = 2, returnTo = arena.start, budget = TaskBudget(ticks = 2300)))
        }
        var reloaded = false; var resumed = false; var resumeAt = -1L; var remaining = -1; var external = 0; var partial = false
        arena.observe { npc, record ->
            val state = checkNotNull(record.primary.transport); val ledger = state.ledger
            partial = partial || ledger.lastTransfer?.partial == true
            check(ledger.valid()) { "live transport ledger violated conservation" }
            check(count(sourceA, Items.OAK_LOG) >= 2 && count(sourceB, Items.OAK_LOG) >= 2) { "transport spent a source reserve" }
            if (!reloaded && ledger.withdrawn > 0) {
                check(TaskService.pause(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                remaining = record.primary.remainingTicks
                val saved = TaskStore.forServer(server).save(CompoundTag())
                check(BehaviorRuntimeService.reload().accepted)
                check(npc.snapshot().navigation == null && npc.snapshot().control == null)
                server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(saved))
                val restored = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
                check(restored.id == record.id && restored.primary.remainingTicks == remaining && restored.primary.transport?.ledger?.withdrawn == ledger.withdrawn)
                resumeAt = npc.snapshot().gameTime + 4; reloaded = true
            }
            if (reloaded && !resumed) {
                check(record.primary.remainingTicks == remaining)
                if (npc.snapshot().gameTime >= resumeAt) { check(TaskService.resume(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED); resumed = true }
            }
            if (ledger.delivered >= 4 && external == 0) {
                val removed = recipientA.removeItem(26, 8); recipientA.setChanged()
                check(removed.`is`(Items.OAK_LOG) && removed.count == 8); external = removed.count
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && record.reason == TaskReason.DELIVERED) { TaskService.status(server, npc.npcUuid).orEmpty() }
                check(reloaded && resumed && partial && external == 8)
                check(ledger.withdrawn == 24 && ledger.delivered == 24 && ledger.retained == 5 && ledger.protected == 5)
                check(ledger.deliveries == mapOf(pos(recipientA) to 12, pos(recipientB) to 12))
                check(count(sourceA, Items.OAK_LOG) == 2 && count(sourceB, Items.OAK_LOG) == 50)
                check(count(recipientA, Items.OAK_LOG) == 64 && count(recipientB, Items.OAK_LOG) == 12)
                val held = npc.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:oak_log") it.stack.count else 0 }
                check(held == 5 && held + count(sourceA, Items.OAK_LOG) + count(sourceB, Items.OAK_LOG) + count(recipientA, Items.OAK_LOG) + count(recipientB, Items.OAK_LOG) + external == 141)
                check(TaskNavigator.distanceSquared(npc.snapshot().position, arena.start) <= 0.75 * 0.75 && record.primary.remainingTicks < remaining)
                arena.succeed(npc, record)
            }
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1800, batch = "wood_separate_supplies")
    fun finiteWoodTakesEquipmentOnlyFromItsAuthorizedAlternativeSourceAndDeliversElsewhere(helper: GameTestHelper) = wood(helper, false)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1800, batch = "wood_carried_supplies")
    fun carriedOnlyWoodCannotWithdrawEquipmentFromItsOutputOrNearbySources(helper: GameTestHelper) = wood(helper, true)

    private fun wood(helper: GameTestHelper, carriedOnly: Boolean) {
        val server = helper.level.server
        val arena = CombatGameTestArena(helper)
        val emptySource = chest(helper, 2, -3); val supplies = chest(helper, 2, 3); val output = chest(helper, 16, 0)
        supplies.setItem(0, ItemStack(Items.IRON_AXE)); output.setItem(0, ItemStack(Items.DIAMOND_AXE))
        helper.setBlock(BlockPos(8, 0, 0), Blocks.GRASS_BLOCK)
        for (y in 1..3) helper.setBlock(BlockPos(8, y, 0), Blocks.OAK_LOG)
        helper.setBlock(BlockPos(11, 1, 0), Blocks.OAK_LOG)
        if (carriedOnly) arena.body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_AXE))
        val wood = WoodSelection(listOf("samcnpc:oak"))
        arena.onReady { npc ->
            arena.assign(npc, LumberjackTaskDefinition(npc.snapshot().dimensionId,
                WorkArea(WorkBox(absolute(helper, 7, 1, -1), absolute(helper, 9, 5, 1))), wood, pos(output), 3,
                budget = TaskBudget(ticks = 1700), version = 2,
                supplySources = if (carriedOnly) null else ContainerChoices(listOf(pos(emptySource), pos(supplies)))))
        }
        var reloaded = false
        arena.observe { npc, record ->
            check(count(output, Items.DIAMOND_AXE) == 1) { "wood task withdrew from its output without supply permission" }
            if (carriedOnly || reloaded) check(count(supplies, Items.IRON_AXE) == if (carriedOnly) 1 else 0)
            val carriedAxe = npc.inventoryContents().any { it.stack.itemId == "minecraft:iron_axe" && it.stack.count == 1 }
            if (!reloaded && carriedAxe) {
                val before = record.primary.remainingTicks
                val saved = TaskStore.forServer(server).save(CompoundTag())
                check(BehaviorRuntimeService.reload().accepted)
                server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(saved))
                val restored = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
                check(restored.id == record.id && restored.primary.remainingTicks == before)
                check(restored.primary.lumberjack?.supplies?.selected == if (carriedOnly) null else pos(supplies))
                reloaded = true
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && record.reason == TaskReason.DELIVERED) { TaskService.status(server, npc.npcUuid).orEmpty() }
                check(reloaded && count(output, Items.OAK_LOG) == 3 && record.primary.lumberjack?.resources?.delivered(wood) == 3)
                check(count(emptySource, Items.OAK_LOG) == 0 && count(supplies, Items.OAK_LOG) == 0)
                check(count(supplies, Items.IRON_AXE) == if (carriedOnly) 1 else 0)
                check(helper.getBlockState(BlockPos(11, 1, 0)).`is`(Blocks.OAK_LOG))
                for (y in 1..3) check(helper.getBlockState(BlockPos(8, y, 0)).isAir)
                check(record.primary.lumberjack?.resources?.entries?.values?.all { it.valid() } == true)
                arena.succeed(npc, record)
            }
        }
    }
    private fun chest(helper: GameTestHelper, x: Int, z: Int): ChestBlockEntity {
        val relative = BlockPos(x, 1, z); helper.setBlock(relative, Blocks.CHEST)
        return helper.level.getBlockEntity(helper.absolutePos(relative)) as ChestBlockEntity
    }
    private fun count(chest: ChestBlockEntity, item: net.minecraft.world.item.Item): Int = (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(item)) chest.getItem(it).count else 0 }
    private fun pos(chest: ChestBlockEntity) = NpcBlockPosition(chest.blockPos.x, chest.blockPos.y, chest.blockPos.z)
    private fun absolute(helper: GameTestHelper, x: Int, y: Int, z: Int): NpcBlockPosition {
        val p = helper.absolutePos(BlockPos(x, y, z)); return NpcBlockPosition(p.x, p.y, p.z)
    }
}
