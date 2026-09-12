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

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object SharedSiteGameTests {
    @JvmStatic
    @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2600,batch="shared_iron_vein")
    fun twoMinersWorkTheSameVeinWithoutDuplicateRemovalOrInitialStockDelivery(h: GameTestHelper) {
        val scene=SharedSiteArena(h)
        val output=scene.chest(0,7)
        val cells=(10..13).map { scene.block(it,1,0) }.toSet()
        for(x in 10..13) h.setBlock(BlockPos(x,1,0),Blocks.IRON_ORE)
        h.setBlock(BlockPos(16,1,0),Blocks.IRON_ORE)
        var competed=false
        scene.onReady { first,second ->
            for(npc in listOf(first,second)) {
                scene.give(npc,ItemStack(Items.IRON_PICKAXE))
                scene.give(npc,ItemStack(Items.RAW_IRON,5))
                val at=npc.snapshot()
                scene.assign(npc,MiningTaskDefinition(at.dimensionId,
                    MiningWorkOrder(WorkArea(WorkBox(scene.block(10,1,0),scene.block(13,1,0))),MiningMethod.EXPOSED,WorkResourceIds(listOf("minecraft:iron_ore"))),
                    WorkResourceIds(listOf("minecraft:raw_iron")),ContainerChoices(listOf(scene.block(0,1,7))),2,
                    MiningCounting.DELIVERED_ITEMS,at.position,returnTo=at.position,budget=TaskBudget(2400)))
            }
        }
        scene.observe { first,a,second ->
            val b=scene.record(second)
            val mineA=checkNotNull(a.primary.mining); val mineB=checkNotNull(b.primary.mining)
            if(mineA.target != null && mineA.target?.position == mineB.target?.position) competed=true
            scene.requireNoFailure(a,b)
            if(a.status.terminal && b.status.terminal) {
                check(competed) { "miners never selected the same pending work cell" }
                val removedA=mineA.selection.removed.keys; val removedB=mineB.selection.removed.keys
                check(removedA.size == 2 && removedB.size == 2 && removedA.intersect(removedB).isEmpty() && removedA+removedB == cells)
                check(scene.count(output,Items.RAW_IRON) == 4)
                for((npc,state) in listOf(first to mineA,second to mineB)) {
                    check(TaskDelivery.inventoryCount(npc,"minecraft:raw_iron") == 5)
                    check(state.resources.physical.entries["minecraft:raw_iron"]?.delivered == 2)
                    check(state.resources.physical.entries.values.all { it.valid() })
                }
                for(x in 10..13) check(h.getBlockState(BlockPos(x,1,0)).isAir)
                check(h.getBlockState(BlockPos(16,1,0)).`is`(Blocks.IRON_ORE))
                scene.finish(first,a,second,b)
            }
        }
    }

    @JvmStatic
    @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=3200,batch="shared_crop_field")
    fun twoFarmersShareMatureCropsAndFiniteSeedSuppliesWithoutDoubleHarvestOrSowing(h: GameTestHelper) {
        val scene=SharedSiteArena(h)
        val output=scene.chest(0,7); val source=scene.chest(3,6)
        source.setItem(0,ItemStack(Items.WHEAT_SEEDS,16)); source.setChanged()
        val sources=ContainerChoices(listOf(scene.block(3,1,6)))
        val cells=(10..13).map { scene.block(it,1,0) }.toSet()
        for(x in 10..13) {
            h.setBlock(BlockPos(x,0,0),Blocks.FARMLAND)
            h.setBlock(BlockPos(x,1,0),(Blocks.WHEAT as CropBlock).getStateForAge(7))
        }
        h.setBlock(BlockPos(14,0,0),Blocks.WATER)
        h.setBlock(BlockPos(17,0,0),Blocks.FARMLAND)
        h.setBlock(BlockPos(17,1,0),(Blocks.WHEAT as CropBlock).getStateForAge(7))
        var competed=false
        scene.onReady { first,second ->
            for(npc in listOf(first,second)) {
                scene.give(npc,ItemStack(Items.IRON_HOE)); scene.give(npc,ItemStack(Items.WHEAT,5))
                val at=npc.snapshot()
                val work=FarmWorkOrder(WorkArea(WorkBox(scene.block(10,1,0),scene.block(13,1,0))),FarmCrop.WHEAT,FarmMode.REPLANT,
                    seedSources=sources,keepSeeds=1,sourceKeepSeeds=2)
                scene.assign(npc,FarmTaskDefinition(at.dimensionId,work,ContainerChoices(listOf(scene.block(0,1,7))),1,
                    at.position,returnTo=at.position,budget=TaskBudget(3000)))
                // Both acquire genuine seed reserves from the same finite source before
                // native random seed drops can satisfy their later planting needs.
                val supply=InventoryTaskDefinition(at.dimensionId,SupplyStock(listOf(StockNeed("minecraft:wheat_seeds",2,2,2)),sources),
                    at.position,at.position,travelRadius=64.0,workTicks=800,budget=TaskBudget(1000))
                check(TaskService.interrupt(h.level.server,npc.npcUuid,supply).status == NpcActionStatus.SUCCEEDED)
            }
        }
        scene.observe { first,a,second ->
            val b=scene.record(second)
            val farmA=checkNotNull(a.primary.farming); val farmB=checkNotNull(b.primary.farming)
            if(farmA.target != null && farmA.target == farmB.target) competed=true
            scene.requireNoFailure(a,b)
            if(a.status.terminal && b.status.terminal) {
                check(competed) { "farmers never competed for a pending crop" }
                check(farmA.harvested.isNotEmpty() && farmB.harvested.isNotEmpty()) { "one farmer harvested nothing: A=${farmA.harvested} B=${farmB.harvested}" }
                check(farmA.harvested.keys.intersect(farmB.harvested.keys).isEmpty() && farmA.harvested.keys+farmB.harvested.keys == cells) { "harvest cells differ: A=${farmA.harvested} B=${farmB.harvested} expected=$cells" }
                check(farmA.replanted == farmA.harvested && farmB.replanted == farmB.harvested) { "replant differs: A=${farmA.replanted}/${farmA.harvested} B=${farmB.replanted}/${farmB.harvested}" }
                check(farmA.planted.values.sum()+farmB.planted.values.sum() == 4)
                // A field cycle delivers all actual yield; the requested quantity is a minimum.
                check(scene.count(output,Items.WHEAT) == 4) { "physical wheat delivery=${scene.count(output,Items.WHEAT)} expected=4" }
                var supplied=0
                for((npc,state) in listOf(first to farmA,second to farmB)) {
                    check(TaskDelivery.inventoryCount(npc,"minecraft:wheat") == 5)
                    check(TaskDelivery.inventoryCount(npc,"minecraft:wheat_seeds") >= 1)
                    val seed=checkNotNull(state.resources.physical.entries["minecraft:wheat_seeds"])
                    check(seed.consumed == state.harvested.size && seed.supplied >= 2) { "seed accounting=$seed harvested=${state.harvested}" }
                    supplied+=seed.supplied
                    check(state.resources.physical.entries.values.all { it.valid() })
                }
                check(scene.count(source,Items.WHEAT_SEEDS) == 16-supplied && scene.count(source,Items.WHEAT_SEEDS) >= 2)
                check(listOf(a,b).all { it.logistics.outcomes.isNotEmpty() && it.logistics.outcomes.all { row -> row.returned } }) { "logistics outcomes: A=${a.logistics.outcomes} B=${b.logistics.outcomes}" }
                for(x in 10..13) check(h.getBlockState(BlockPos(x,1,0)).`is`(Blocks.WHEAT))
                check(h.getBlockState(BlockPos(17,1,0)).getValue(CropBlock.AGE) == 7)
                scene.finish(first,a,second,b)
            }
        }
    }

    @JvmStatic
    @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2600,batch="shared_planting_sites")
    fun twoPlantersCompleteDifferentLayoutsAfterSelectingTheSameEmptySite(h: GameTestHelper) {
        val scene=SharedSiteArena(h)
        val source=scene.chest(3,6)
        source.setItem(0,ItemStack(Items.OAK_SAPLING,10)); source.setChanged()
        for(x in listOf(10,16,22,28)) h.setBlock(BlockPos(x,0,0),Blocks.DIRT)
        var competed=false
        scene.onReady { first,second ->
            for(npc in listOf(first,second)) {
                val at=npc.snapshot()
                val work=PlantingWorkOrder(WorkArea(WorkBox(scene.block(10,1,0),scene.block(28,1,0))),SaplingSpecies.OAK,PlantingMode.GAPS,
                    sources=ContainerChoices(listOf(scene.block(3,1,6))),keepSaplings=1,sourceKeep=2)
                scene.assign(npc,PlantingTaskDefinition(at.dimensionId,work,1,at.position,returnTo=at.position,budget=TaskBudget(2400)))
            }
        }
        scene.observe { first,a,second ->
            val b=scene.record(second)
            val plantA=checkNotNull(a.primary.planting); val plantB=checkNotNull(b.primary.planting)
            if(plantA.selected != null && plantA.selected == plantB.selected) competed=true
            scene.requireNoFailure(a,b)
            if(a.status.terminal && b.status.terminal) {
                check(competed)
                val placedA=plantA.plots.values.flatMap { it.placed }.toSet(); val placedB=plantB.plots.values.flatMap { it.placed }.toSet()
                check(placedA.size == 1 && placedB.size == 1 && placedA.intersect(placedB).isEmpty())
                check(plantA.completed(a.primary.definition as PlantingTaskDefinition) == 1 && plantB.completed(b.primary.definition as PlantingTaskDefinition) == 1)
                check(scene.count(source,Items.OAK_SAPLING) == 6)
                for((npc,state) in listOf(first to plantA,second to plantB)) {
                    check(TaskDelivery.inventoryCount(npc,"minecraft:oak_sapling") == 1)
                    val row=checkNotNull(state.resources.entries["minecraft:oak_sapling"])
                    check(row.supplied == 2 && row.consumed == 1 && row.retained == 1 && row.valid())
                }
                for(x in listOf(10,16,22,28)) {
                    val cell=scene.block(x,1,0)
                    check(if(cell in placedA+placedB) h.getBlockState(BlockPos(x,1,0)).`is`(Blocks.OAK_SAPLING) else h.getBlockState(BlockPos(x,1,0)).isAir)
                }
                scene.finish(first,a,second,b)
            }
        }
    }

    @JvmStatic
    @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=3000,batch="shared_wood_and_planting")
    fun newSaplingSurvivesWhileAnotherNpcContinuesWoodWorkInTheSameArea(h: GameTestHelper) {
        val scene=SharedSiteArena(h,10.0,4.0)
        val output=scene.chest(0,7)
        for(x in listOf(10,14)) {
            h.setBlock(BlockPos(x,0,0),Blocks.DIRT)
            for(y in 1..3) h.setBlock(BlockPos(x,y,0),Blocks.OAK_LOG)
        }
        val base=scene.block(10,1,0)
        val originalTrunks=listOf(10,14).flatMap { x -> (1..3).map { y -> scene.block(x,y,0) } }.toSet()
        var assigned=false; var overlapped=false
        scene.onReady { wood,planter ->
            scene.give(wood,ItemStack(Items.IRON_AXE)); scene.give(wood,ItemStack(Items.IRON_SHOVEL))
            scene.give(wood,ItemStack(Items.COARSE_DIRT,8)); scene.give(wood,ItemStack(Items.OAK_LOG,5))
            scene.give(planter,ItemStack(Items.OAK_SAPLING,2))
            scene.assign(wood,LumberjackTaskDefinition(wood.snapshot().dimensionId,
                WorkArea(WorkBox(scene.block(8,1,-2),scene.block(16,5,2))),WoodSelection(listOf("samcnpc:oak")),scene.block(0,1,7),6,
                budget=TaskBudget(2800),version=2))
        }
        scene.observe { wood,a,planter ->
            val state=checkNotNull(a.primary.lumberjack)
            if(!assigned && base in state.observedRemovedBlocks && h.getBlockState(BlockPos(10,1,0)).isAir) {
                val at=planter.snapshot()
                scene.assign(planter,PlantingTaskDefinition(at.dimensionId,
                    PlantingWorkOrder(WorkArea(WorkBox(base,base)),SaplingSpecies.OAK,PlantingMode.GAPS,keepSaplings=1),
                    1,at.position,returnTo=at.position,budget=TaskBudget(2400)))
                assigned=true
            }
            if(assigned) {
                val b=scene.record(planter); val planting=checkNotNull(b.primary.planting)
                scene.requireNoFailure(a,b)
                if(planting.planted() == 1) {
                    check(h.getBlockState(BlockPos(10,1,0)).`is`(Blocks.OAK_SAPLING)) { "wood worker removed the new planting" }
                    if(!a.status.terminal) overlapped=true
                }
                if(a.status.terminal && b.status.terminal) {
                    check(overlapped && state.observedRemovedBlocks.intersect(originalTrunks) == originalTrunks && planting.planted() == 1)
                    check(scene.count(output,Items.OAK_LOG) == 6 && TaskDelivery.inventoryCount(wood,"minecraft:oak_log") == 5)
                    check(TaskDelivery.inventoryCount(planter,"minecraft:oak_sapling") == 1 && planting.resources.entries["minecraft:oak_sapling"]?.consumed == 1)
                    check(state.resources.entries.values.all { it.valid() } && planting.resources.entries.values.all { it.valid() })
                    for(x in listOf(10,14)) for(y in 1..3) if(x != 10 || y != 1) check(h.getBlockState(BlockPos(x,y,0)).isAir)
                    scene.finish(wood,a,planter,b)
                }
            } else check(!a.status.terminal) { "wood task ended before the supplied planting handoff: ${a.detail}" }
        }
    }
}

/** Both bodies are created before any scheduled callback; one arena owns world preparation. */
private class SharedSiteArena(val helper: GameTestHelper,secondX: Double=0.0,secondZ: Double=4.0) {
    private val arena=CombatGameTestArena(helper)
    private val secondBody=checkNotNull(arena.body.type.create(helper.level)) as LivingEntity
    init {
        secondBody.moveTo(arena.start.x+secondX,arena.start.y,arena.start.z+secondZ,-90.0F,0.0F)
        check(helper.level.addFreshEntity(secondBody))
    }
    private fun secondary(): NpcFacade {
        val service=CoreNpcApi.service(helper.level.server)
        return checkNotNull(service.find(secondBody.uuid)?.let(service::runtime))
    }
    fun onReady(action:(NpcFacade,NpcFacade)->Unit) = arena.onReady { first ->
        val second=secondary(); check(second.snapshot().onGround); action(first,second)
    }
    fun observe(action:(NpcFacade,TaskRecord,NpcFacade)->Unit) = arena.observe { first,record -> action(first,record,secondary()) }
    fun record(npc: NpcFacade)=checkNotNull(TaskStore.forServer(helper.level.server).get(npc.npcUuid))
    fun assign(npc: NpcFacade,definition: TaskDefinition) = arena.assign(npc,definition)
    fun give(npc: NpcFacade,stack: ItemStack) {
        val at=npc.snapshot().position
        val drop=ItemEntity(helper.level,at.x,at.y,at.z,stack); drop.setNoPickUpDelay()
        check(helper.level.addFreshEntity(drop)); check(npc.pickupItem(drop.uuid).status == NpcActionStatus.SUCCEEDED)
    }
    fun block(x: Int,y: Int,z: Int): NpcBlockPosition {
        val p=helper.absolutePos(BlockPos(x,y,z)); return NpcBlockPosition(p.x,p.y,p.z)
    }
    fun chest(x: Int,z: Int): ChestBlockEntity {
        helper.setBlock(BlockPos(x,1,z),Blocks.CHEST)
        return helper.level.getBlockEntity(helper.absolutePos(BlockPos(x,1,z))) as ChestBlockEntity
    }
    fun count(chest: ChestBlockEntity,item: Item)=(0 until chest.containerSize).sumOf { if(chest.getItem(it).`is`(item)) chest.getItem(it).count else 0 }
    fun requireNoFailure(vararg records: TaskRecord) {
        for(record in records) check(!record.status.terminal || record.status == TaskStatus.COMPLETED) { TaskService.status(helper.level.server,record.npcUuid).orEmpty() }
    }
    fun finish(first: NpcFacade,a: TaskRecord,second: NpcFacade,b: TaskRecord) {
        requireNoFailure(a,b)
        for((npc,record) in listOf(first to a,second to b)) {
            check(TaskCodec.read(TaskCodec.write(record)).report() == record.report())
            val state=npc.snapshot()
            check(state.control == null && state.navigation == null && state.blockBreak == null && state.itemUse == null)
            check(BehaviorRuntimeService.assignedPacks(helper.level.server,npc.npcUuid).isEmpty())
        }
        secondBody.discard(); arena.succeed(first,a)
    }
}
