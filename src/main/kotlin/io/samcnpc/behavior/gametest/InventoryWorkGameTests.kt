package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.*
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object InventoryWorkGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3500, batch = "inventory_work_mixed")
    fun unloadSupplyPickupReloadAndQueuedGoalChangePreserveOnePrimaryIntent(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level, "StockProof")
        val stranger = GameTestActor(helper.level, "NoStockRights")
        val arena = CombatGameTestArena(helper, actor.player); val server = helper.level.server
        val source = chest(helper, 8, 2); source.setItem(0, ItemStack(Items.BREAD, 32))
        val output = chest(helper, 6, -2)
        val supply = SupplyStock(listOf(StockNeed("minecraft:bread",2,8), StockNeed("minecraft:cobblestone",2,4)), choices(helper,8,2))
        val unload = UnloadExcess(listOf(ItemReserve("minecraft:cobblestone",4)), choices(helper,6,-2))
        var taskId: UUID? = null; var frameId: UUID? = null; var inventoryFrame: UUID? = null
        var reloaded = false; var spawnedPickup = false; var oversized: ItemEntity? = null; var goalChange: TaskAmendmentRequest? = null
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.IRON_AXE)); arena.give(npc, ItemStack(Items.COBBLESTONE,20))
            arena.assign(npc, NavigateTaskDefinition(npc.snapshot().dimensionId, offset(arena.start,24.0,0.0), budget = TaskBudget(3200)))
            val record = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid)); taskId = record.id; frameId = record.primary.id
            val policy = TaskLogisticsPolicy(arena.start, supply, unload, PickupNearby(listOf("minecraft:iron_ingot"),4.0,8),
                travelRadius = 32.0, workTicks = 800, durationTicks = 1400, cooldownTicks = 20)
            val denied = TaskAmendments.automatic(server,npc,stranger.player,TaskChange.Logistics(policy))
            check(denied.status != NpcActionStatus.SUCCEEDED && !record.logistics.policy.enabled)
            check(TaskAmendments.automatic(server,npc,actor.player,TaskChange.Logistics(policy)).status == NpcActionStatus.SUCCEEDED)
            check(record.amendments.revision == 1)
        }
        arena.observe { npc, record ->
            check(record.id == taskId && record.primary.id == frameId)
            val active = record.active.inventory
            if (!reloaded && active?.resources?.entries?.get("minecraft:cobblestone")?.delivered == 16) {
                inventoryFrame = record.active.id
                val replacement = (record.primary.definition as NavigateTaskDefinition).copy(destination = offset(arena.start,26.0,0.0))
                val now = npc.snapshot().gameTime
                val request = TaskAmendmentRequest(record.id,UUID.randomUUID(),actor.player.uuid,record.amendments.revision,now,now+1200,TaskChange.Replace(replacement))
                check(TaskAmendments.request(server,npc,actor.player,request).status == NpcActionStatus.SUCCEEDED)
                check(record.amendments.pending?.requestId == request.requestId && record.amendments.revision == 1)
                goalChange = request
                check(TaskService.pause(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                val saved = TaskStore.forServer(server).save(CompoundTag())
                check(BehaviorRuntimeService.reload().accepted)
                server.overworld().dataStorage.set("samcnpc_behavior_tasks",TaskStore.load(saved))
                check(TaskService.resume(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                val restored = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
                check(restored.active.id == inventoryFrame && restored.active.inventory?.resources?.entries?.get("minecraft:cobblestone")?.delivered == 16)
                check(count(output,Items.COBBLESTONE) == 16)
                reloaded = true
            }
            if (!spawnedPickup && record.logistics.outcomes.size == 2 && record.logistics.cooldownRemaining <= 1) {
                // Start the deliberate-detour proof after cooldown, before any native contact pickup.
                val current = npc.snapshot().position
                val drop = ItemEntity(helper.level,current.x + 2.5,current.y,current.z + 1.0,ItemStack(Items.IRON_INGOT,4))
                drop.setNoPickUpDelay(); check(helper.level.addFreshEntity(drop))
                val large = ItemEntity(helper.level,current.x - 1.0,current.y,current.z - 3.5,ItemStack(Items.IRON_INGOT,32))
                large.setNoPickUpDelay(); check(helper.level.addFreshEntity(large)); oversized = large; spawnedPickup = true
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && reloaded && spawnedPickup) { TaskService.status(server,npc.npcUuid).orEmpty() }
                val reports = record.logistics.outcomes
                check(reports.size == 3 && reports.all { it.returned && it.reason == InventoryWorkReason.SATISFIED }) { reports.toString() }
                check(reports[0].frameId == inventoryFrame && reports[0].revision == 1 && reports[0].unloaded == mapOf("minecraft:cobblestone" to 16))
                check(reports[1].supplied == mapOf("minecraft:bread" to 8) && reports[1].revision == 2)
                check(reports[2].picked == mapOf("minecraft:iron_ingot" to 4))
                check(reports[0].recipients == mapOf(block(helper,6,1,-2) to mapOf("minecraft:cobblestone" to 16)))
                check(reports[1].sources == mapOf(block(helper,8,1,2) to mapOf("minecraft:bread" to 8)))
                check(server.commands.performPrefixedCommand(actor.player.createCommandSourceStack(),"samcnpc behavior task inventory_history AmendProof 1") == 1)
                check(record.amendments.revision == 2 && record.amendments.pending == null)
                check(record.amendments.replay(checkNotNull(goalChange))?.startsWith("APPLIED") == true)
                check(record.totalFailures == 0 && record.primary.remainingTicks < 3200 && record.completedInterruptions == 3)
                check(count(source,Items.BREAD) == 24 && count(output,Items.COBBLESTONE) == 16)
                check(TaskDelivery.inventoryCount(npc,"minecraft:cobblestone") == 4 && TaskDelivery.inventoryCount(npc,"minecraft:bread") == 8)
                check(TaskDelivery.inventoryCount(npc,"minecraft:iron_ingot") == 4 && TaskDelivery.inventoryCount(npc,"minecraft:iron_axe") == 1)
                check(oversized?.isAlive == true && oversized?.item?.count == 32)
                oversized?.discard(); stranger.close(); actor.close(); arena.succeed(npc,record)
            }
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1400, batch = "inventory_empty_source_resume")
    fun manualPolicyCommandReportsEmptySourceAndResumesTheOriginalNavigation(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level,"StockCommand")
        val arena = CombatGameTestArena(helper,actor.player); val server = helper.level.server
        chest(helper,4,2)
        var id: UUID? = null
        arena.onReady { npc ->
            arena.assign(npc,NavigateTaskDefinition(npc.snapshot().dimensionId,offset(arena.start,20.0,0.0),budget=TaskBudget(1200)))
            id = TaskStore.forServer(server).get(npc.npcUuid)?.id
            val p = block(helper,4,1,2)
            check(server.commands.performPrefixedCommand(actor.player.createCommandSourceStack(),
                "samcnpc behavior task logistics AmendProof supply \"${p.x},${p.y},${p.z}\" \"minecraft:bread=2/8\"") == 1)
            check(server.commands.performPrefixedCommand(actor.player.createCommandSourceStack(),
                "samcnpc behavior task logistics AmendProof limits 16 200 500 6000") == 1)
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            check(record.status == TaskStatus.COMPLETED && record.id == id && record.amendments.revision == 2) { TaskService.status(server,npc.npcUuid).orEmpty() }
            val report = record.logistics.outcomes.single()
            check(report.reason == InventoryWorkReason.SOURCE_EMPTY && report.returned && report.supplied.isEmpty())
            check(record.totalFailures == 0 && record.completedInterruptions == 1 && record.primary.remainingTicks < 1200)
            actor.close(); arena.succeed(npc,record)
        } }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1400, batch = "inventory_partial_supply")
    fun supplyKeepsTheSourceReserveAndReportsOnlyItsActualPartialTransfer(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level,"SupplyCommand")
        val arena = CombatGameTestArena(helper,actor.player); val source = chest(helper,4,2); source.setItem(0,ItemStack(Items.BREAD,10))
        arena.onReady { npc ->
            val p = block(helper,4,1,2)
            check(helper.level.server.commands.performPrefixedCommand(actor.player.createCommandSourceStack(),
                "samcnpc behavior task assign AmendProof supply \"${p.x},${p.y},${p.z}\" \"minecraft:bread=2/8/3\"") == 1)
            check(TaskStore.forServer(helper.level.server).get(npc.npcUuid)?.primary?.definition is InventoryTaskDefinition)
        }
        arena.observe { npc, record -> if (record.status.terminal) {
            val outcome = record.logistics.outcomes.single()
            check(record.status == TaskStatus.FAILED && record.reason == TaskReason.INVENTORY_INCOMPLETE && outcome.returned) { record.detail }
            check(outcome.reason == InventoryWorkReason.SOURCE_EMPTY && outcome.supplied == mapOf("minecraft:bread" to 7)) { outcome.toString() }
            check(count(source,Items.BREAD) == 3 && TaskDelivery.inventoryCount(npc,"minecraft:bread") == 7)
            check(record.totalFailures == 0 && record.primary.remainingTicks < 1200)
            actor.close(); arena.succeed(npc,record)
        } }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2200, batch = "inventory_capacity")
    fun fullInventoryAndFullRecipientLeaveActualItemsAndEquipmentIntact(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper); val source = chest(helper,2,2); source.setItem(0,ItemStack(Items.BREAD,16))
        val output = chest(helper,2,-2); for (slot in 0 until output.containerSize) output.setItem(slot,ItemStack(Items.STONE,64))
        var supplyChecked = false
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_AXE)); repeat(35) { arena.give(npc,ItemStack(Items.COBBLESTONE,64)) }
            arena.assign(npc,InventoryTaskDefinition(npc.snapshot().dimensionId,SupplyStock(listOf(StockNeed("minecraft:bread",1,1)),choices(helper,2,2)),arena.start))
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            check(record.status == TaskStatus.FAILED && record.reason == TaskReason.INVENTORY_INCOMPLETE)
            val report = record.logistics.outcomes.single(); check(report.returned && report.supplied.isEmpty() && report.unloaded.isEmpty())
            check(TaskDelivery.inventoryCount(npc,"minecraft:cobblestone") == 2240 && TaskDelivery.inventoryCount(npc,"minecraft:iron_axe") == 1)
            check(count(source,Items.BREAD) == 16 && count(output,Items.STONE) == 1728)
            if (!supplyChecked) {
                check(report.reason == InventoryWorkReason.INVENTORY_FULL) { report.toString() }; supplyChecked = true
                check(TaskCodec.read(TaskCodec.write(record)).report() == record.report())
                arena.assign(npc,InventoryTaskDefinition(npc.snapshot().dimensionId,UnloadExcess(listOf(ItemReserve("minecraft:cobblestone",0)),choices(helper,2,-2)),arena.start))
            } else {
                check(report.reason == InventoryWorkReason.STORAGE_FULL) { report.toString() }; arena.succeed(npc,record)
            }
        } }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3500, batch = "inventory_wood_provenance")
    fun woodSuppliesAndUnrelatedUnloadRemainSeparateFromTheHarvestQuota(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level,"WoodStock")
        val arena = CombatGameTestArena(helper,actor.player); val server = helper.level.server
        val source = chest(helper,3,3); source.setItem(0,ItemStack(Items.BREAD,8))
        val extras = chest(helper,3,-3); val output = chest(helper,16,0)
        for (y in 1..3) helper.setBlock(BlockPos(8,y,0),Blocks.OAK_LOG)
        val wood = WoodSelection(listOf("samcnpc:oak"))
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_AXE)); arena.give(npc,ItemStack(Items.COBBLESTONE,12))
            arena.assign(npc,LumberjackTaskDefinition(npc.snapshot().dimensionId,WorkArea(WorkBox(block(helper,6,1,-2),block(helper,10,5,2))),wood,
                block(helper,16,1,0),3,budget=TaskBudget(3200),version=2))
            val policy = TaskLogisticsPolicy(arena.start,SupplyStock(listOf(StockNeed("minecraft:bread",2,4)),choices(helper,3,3)),
                UnloadExcess(listOf(ItemReserve("minecraft:cobblestone",0),ItemReserve("minecraft:oak_log",0)),choices(helper,3,-3)),
                travelRadius=32.0,cooldownTicks=20)
            check(TaskAmendments.automatic(server,npc,actor.player,TaskChange.Logistics(policy)).status == NpcActionStatus.SUCCEEDED)
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            check(record.status == TaskStatus.COMPLETED) { TaskService.status(server,npc.npcUuid).orEmpty() }
            val state = checkNotNull(record.primary.lumberjack); val counts = state.resources.entries
            check(record.logistics.outcomes.size == 2 && record.logistics.outcomes.all { it.returned }) { record.logistics.outcomes.toString() }
            check(counts["minecraft:bread"]?.supplied == 4 && counts["minecraft:bread"]?.gathered == 0)
            check(counts["minecraft:cobblestone"]?.delivered == 12 && state.resources.delivered(wood) == 3 && !state.resources.uncertain)
            check(count(source,Items.BREAD) == 4 && count(extras,Items.COBBLESTONE) == 12 && count(extras,Items.OAK_LOG) == 0)
            check(count(output,Items.OAK_LOG) == 3 && state.deliveries[block(helper,3,1,-3)]?.get("minecraft:cobblestone") == 12)
            check(TaskDelivery.inventoryCount(npc,"minecraft:bread") == 4 && record.totalFailures == 0)
            actor.close(); arena.succeed(npc,record)
        } }
    }
    private fun chest(helper: GameTestHelper,x: Int,z: Int): ChestBlockEntity {
        helper.setBlock(BlockPos(x,1,z),Blocks.CHEST)
        return helper.level.getBlockEntity(helper.absolutePos(BlockPos(x,1,z))) as ChestBlockEntity
    }
    private fun count(chest: ChestBlockEntity,item: Item) = (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(item)) chest.getItem(it).count else 0 }
    private fun choices(helper: GameTestHelper,x: Int,z: Int) = ContainerChoices(listOf(block(helper,x,1,z)))
    private fun block(helper: GameTestHelper,x: Int,y: Int,z: Int): NpcBlockPosition { val p=helper.absolutePos(BlockPos(x,y,z)); return NpcBlockPosition(p.x,p.y,p.z) }
    private fun offset(p: NpcPosition,x: Double,z: Double) = NpcPosition(p.x+x,p.y,p.z+z)
}
