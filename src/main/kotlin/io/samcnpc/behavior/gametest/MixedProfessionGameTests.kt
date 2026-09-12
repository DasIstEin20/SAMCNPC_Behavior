package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object MixedProfessionGameTests {
    @JvmStatic
    @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=4200,batch="mixed_mining_farm_wood")
    fun threeProfessionsShareFiniteSuppliesAndOutputWhileOnePauses(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper)
        val server=helper.level.server
        val minerBody=extra(helper,arena,-4.0)
        val woodBody=extra(helper,arena,4.0)
        val source=chest(helper,3,3)
        val output=chest(helper,20,3)
        val sources=ContainerChoices(listOf(pos(helper,3,1,3)))
        val destinations=ContainerChoices(listOf(pos(helper,20,1,3)))
        val sourceStock=listOf(ItemStack(Items.IRON_PICKAXE),ItemStack(Items.IRON_HOE),ItemStack(Items.IRON_AXE),
            ItemStack(Items.IRON_SHOVEL),ItemStack(Items.COARSE_DIRT,12),ItemStack(Items.WHEAT_SEEDS,16),
            ItemStack(Items.OAK_SAPLING,10),ItemStack(Items.APPLE,7))
        sourceStock.forEachIndexed { slot,stack -> source.setItem(slot,stack) }
        source.setChanged()
        for(x in 12..13) {
            helper.setBlock(BlockPos(x,1,-6),Blocks.IRON_ORE)
            helper.setBlock(BlockPos(x,0,0),Blocks.DIRT)
        }
        helper.setBlock(BlockPos(14,0,0),Blocks.WATER)
        helper.setBlock(BlockPos(12,0,6),Blocks.DIRT)
        for(y in 1..3) helper.setBlock(BlockPos(12,y,6),Blocks.OAK_LOG)
        helper.setBlock(BlockPos(20,1,-6),Blocks.IRON_ORE)
        helper.setBlock(BlockPos(20,0,0),Blocks.FARMLAND)
        helper.setBlock(BlockPos(20,1,0),(Blocks.WHEAT as CropBlock).getStateForAge(1))
        helper.setBlock(BlockPos(20,1,6),Blocks.OAK_LOG)
        val ids=mutableMapOf<UUID,UUID>()
        val remaining=mutableMapOf<UUID,Int>()
        var assigned=false
        var pauseTime: Long?=null
        var pauseRemaining=0
        var pauseProgress=0
        var resumed=false
        var grown=false
        fun supply(npc: NpcFacade,item: String) {
            val snapshot=npc.snapshot()
            val definition=InventoryTaskDefinition(snapshot.dimensionId,SupplyStock(listOf(StockNeed(item,1,1)),sources),
                snapshot.position,snapshot.position,travelRadius=64.0,workTicks=900,budget=TaskBudget(1000))
            check(TaskService.interrupt(server,npc.npcUuid,definition).status == NpcActionStatus.SUCCEEDED)
        }
        arena.onReady { farmer ->
            give(helper,farmer,ItemStack(Items.WHEAT,5))
            val start=farmer.snapshot()
            val work=FarmWorkOrder(WorkArea(WorkBox(pos(helper,12,1,0),pos(helper,13,1,0))),FarmCrop.WHEAT,FarmMode.CULTIVATE,
                prepareSoil=true,seedSources=sources,keepSeeds=1,sourceKeepSeeds=2,growthWaitTicks=600,growthCheckTicks=20)
            arena.assign(farmer,FarmTaskDefinition(start.dimensionId,work,destinations,2,start.position,
                returnTo=start.position,budget=TaskBudget(4000)))
            supply(farmer,"minecraft:iron_hoe")
        }
        arena.observe { farmer,farmRecord ->
            val service=CoreNpcApi.service(server)
            val miner=checkNotNull(service.find(minerBody.uuid)?.let(service::runtime))
            val woodNpc=checkNotNull(service.find(woodBody.uuid)?.let(service::runtime))
            if(!assigned && miner.snapshot().onGround && woodNpc.snapshot().onGround) {
                give(helper,miner,ItemStack(Items.RAW_IRON,5))
                give(helper,woodNpc,ItemStack(Items.OAK_LOG,5))
                val mineStart=miner.snapshot()
                val mine=MiningTaskDefinition(mineStart.dimensionId,
                    MiningWorkOrder(WorkArea(WorkBox(pos(helper,12,1,-6),pos(helper,13,1,-6))),MiningMethod.EXPOSED,WorkResourceIds(listOf("minecraft:iron_ore"))),
                    WorkResourceIds(listOf("minecraft:raw_iron")),destinations,2,MiningCounting.DELIVERED_ITEMS,mineStart.position,
                    returnTo=mineStart.position,budget=TaskBudget(4000))
                check(TaskService.assign(server,miner,mine).status == NpcActionStatus.SUCCEEDED)
                supply(miner,"minecraft:iron_pickaxe")
                val woodStart=woodNpc.snapshot()
                val replant=PlantingTaskDefinition(woodStart.dimensionId,
                    PlantingWorkOrder(WorkArea(WorkBox(pos(helper,12,1,6),pos(helper,12,1,6))),SaplingSpecies.OAK,PlantingMode.GAPS,
                        sources=sources,keepSaplings=1,sourceKeep=2),1,woodStart.position,returnTo=woodStart.position,budget=TaskBudget(4000))
                val wood=LumberjackTaskDefinition(woodStart.dimensionId,WorkArea(WorkBox(pos(helper,10,1,4),pos(helper,14,5,8))),
                    WoodSelection(listOf("samcnpc:oak")),destinations.positions.single(),3,budget=TaskBudget(4000),version=2,supplySources=sources,replant=replant)
                check(TaskService.assign(server,woodNpc,wood).status == NpcActionStatus.SUCCEEDED)
                assigned=true
            }
            if(assigned) {
                val store=TaskStore.forServer(server)
                val mineRecord=checkNotNull(store.get(miner.npcUuid))
                val woodRecord=checkNotNull(store.get(woodNpc.npcUuid))
                val records=listOf(farmRecord,mineRecord,woodRecord)
                val npcs=listOf(farmer,miner,woodNpc)
                val farm=checkNotNull(farmRecord.primary.farming)
                val wood=checkNotNull(woodRecord.primary.lumberjack)
                for(record in records) {
                    val previousId=ids.putIfAbsent(record.npcUuid,record.id)
                    check(previousId == null || previousId == record.id)
                    check(record.amendments.revision == 0)
                    val previousTicks=remaining.put(record.npcUuid,record.primary.remainingTicks)
                    check(previousTicks == null || record.primary.remainingTicks <= previousTicks) { "side work extended original task time" }
                    check(!record.status.terminal || record.status == TaskStatus.COMPLETED) { TaskService.status(server,record.npcUuid).orEmpty() }
                }
                val progress=farm.tilled.values.sum()+farm.resources.physical.entries.values.sumOf { it.supplied }+
                    wood.removedWood.size+wood.resources.entries.values.sumOf { it.supplied }
                val pausedAt=pauseTime
                if(pausedAt == null) {
                    check(TaskService.pause(server,miner.npcUuid).status == NpcActionStatus.SUCCEEDED)
                    pauseTime=farmer.snapshot().gameTime
                    pauseRemaining=mineRecord.primary.remainingTicks
                    pauseProgress=progress
                } else if(!resumed) {
                    check(mineRecord.status == TaskStatus.PAUSED && mineRecord.primary.remainingTicks == pauseRemaining)
                    val age=farmer.snapshot().gameTime-pausedAt
                    check(age <= 240) { "other professions made no physical progress while miner was paused" }
                    if(age >= 40 && progress > pauseProgress) {
                        check(TaskService.resume(server,miner.npcUuid).status == NpcActionStatus.SUCCEEDED)
                        check(mineRecord.primary.remainingTicks == pauseRemaining)
                        resumed=true
                    }
                }
                if(!grown && farm.phase == FarmPhase.WAIT_GROWTH && farm.planted.size == 2) {
                    // Controlled external growth input: this scene tests coordination and accounting, not random growth duration.
                    for(x in 12..13) helper.setBlock(BlockPos(x,1,0),(Blocks.WHEAT as CropBlock).getStateForAge(7))
                    grown=true
                }
                if(records.all { it.status.terminal }) {
                    check(resumed && grown)
                    val mine=checkNotNull(mineRecord.primary.mining)
                    val planting=checkNotNull(woodRecord.primary.planting)
                    check(count(output,Items.RAW_IRON) == 2 && count(output,Items.WHEAT) == 2 && count(output,Items.OAK_LOG) == 3)
                    check(TaskDelivery.inventoryCount(miner,"minecraft:raw_iron") == 5 && TaskDelivery.inventoryCount(farmer,"minecraft:wheat") == 5 && TaskDelivery.inventoryCount(woodNpc,"minecraft:oak_log") == 5)
                    check(mine.resources.physical.entries["minecraft:raw_iron"]?.delivered == 2 && farm.resources.physical.entries["minecraft:wheat"]?.delivered == 2 && wood.resources.entries["minecraft:oak_log"]?.delivered == 3)
                    check(mine.selection.removed.size == 2 && wood.removedWood.size == 3 && farm.harvested.size == 2 && farm.replanted == farm.harvested)
                    check(farm.planted.values.sum() == 4 && farm.resources.physical.entries["minecraft:wheat_seeds"]?.consumed == 4)
                    check(planting.planted() == 1 && planting.completed(checkNotNull(wood.replantDefinition)) == 1 && planting.resources.entries["minecraft:oak_sapling"]?.consumed == 1 && wood.resources.entries["minecraft:oak_sapling"]?.consumed == 1)
                    check(TaskDelivery.inventoryCount(farmer,"minecraft:wheat_seeds") >= 1 && TaskDelivery.inventoryCount(woodNpc,"minecraft:oak_sapling") == 1)
                    val seeds=checkNotNull(farm.resources.physical.entries["minecraft:wheat_seeds"]).supplied
                    check(count(source,Items.WHEAT_SEEDS) == 16-seeds && count(source,Items.WHEAT_SEEDS) >= 2)
                    check(count(source,Items.OAK_SAPLING) == 8 && count(source,Items.APPLE) == 7)
                    check(TaskDelivery.inventoryCount(woodNpc,"minecraft:iron_pickaxe") == 0 && TaskDelivery.inventoryCount(miner,"minecraft:iron_pickaxe") == 1)
                    check(count(source,Items.IRON_PICKAXE) == 0 && count(source,Items.IRON_HOE) == 0 && count(source,Items.IRON_AXE) == 0)
                    check(mine.resources.physical.entries.values.all { it.valid() } && farm.resources.physical.entries.values.all { it.valid() } && wood.resources.entries.values.all { it.valid() } && planting.resources.entries.values.all { it.valid() })
                    check(records.all { it.logistics.outcomes.isNotEmpty() && it.logistics.outcomes.all { row -> row.returned } })
                    for(x in 12..13) {
                        check(helper.getBlockState(BlockPos(x,1,-6)).isAir)
                        check(helper.getBlockState(BlockPos(x,1,0)).`is`(Blocks.WHEAT))
                    }
                    check(helper.getBlockState(BlockPos(12,1,6)).`is`(Blocks.OAK_SAPLING))
                    check(helper.getBlockState(BlockPos(20,1,-6)).`is`(Blocks.IRON_ORE) && helper.getBlockState(BlockPos(20,1,0)).`is`(Blocks.WHEAT) && helper.getBlockState(BlockPos(20,1,6)).`is`(Blocks.OAK_LOG))
                    for((npc,record) in npcs.zip(records)) {
                        check(TaskCodec.read(TaskCodec.write(record)).report() == record.report())
                        val state=npc.snapshot()
                        check(state.control == null && state.navigation == null && state.blockBreak == null && state.itemUse == null)
                        check(BehaviorRuntimeService.assignedPacks(server,npc.npcUuid).isEmpty())
                    }
                    minerBody.discard(); woodBody.discard(); arena.succeed(farmer,farmRecord)
                }
            }
        }
    }
    private fun extra(h: GameTestHelper,arena: CombatGameTestArena,z: Double): LivingEntity {
        val body=checkNotNull(arena.body.type.create(h.level)) as LivingEntity
        body.moveTo(arena.start.x,arena.start.y,arena.start.z+z,-90.0F,0.0F)
        check(h.level.addFreshEntity(body)); return body
    }
    private fun give(h: GameTestHelper,npc: NpcFacade,stack: ItemStack) {
        val p=npc.snapshot().position
        val drop=ItemEntity(h.level,p.x,p.y,p.z,stack); drop.setNoPickUpDelay()
        check(h.level.addFreshEntity(drop)); check(npc.pickupItem(drop.uuid).status == NpcActionStatus.SUCCEEDED)
    }
    private fun chest(h: GameTestHelper,x: Int,z: Int): ChestBlockEntity {
        h.setBlock(BlockPos(x,1,z),Blocks.CHEST)
        return h.level.getBlockEntity(h.absolutePos(BlockPos(x,1,z))) as ChestBlockEntity
    }
    private fun pos(h: GameTestHelper,x: Int,y: Int,z: Int): NpcBlockPosition {
        val p=h.absolutePos(BlockPos(x,y,z)); return NpcBlockPosition(p.x,p.y,p.z)
    }
    private fun count(chest: ChestBlockEntity,item: Item)=(0 until chest.containerSize).sumOf { if(chest.getItem(it).`is`(item)) chest.getItem(it).count else 0 }
}
