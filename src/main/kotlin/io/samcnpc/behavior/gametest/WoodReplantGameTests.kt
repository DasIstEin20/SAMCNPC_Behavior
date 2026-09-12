package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
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

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object WoodReplantGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=3000,batch="wood_oak_replant")
    fun oakWoodDeliveryThenRealReplantSurvivesAPlantingStageReload(h: GameTestHelper)=exercise(h,SaplingSpecies.OAK,false)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=3000,batch="wood_birch_replant")
    fun birchWoodDeliveryUsesItsOwnActualSaplingAtTheConfirmedCutBase(h: GameTestHelper)=exercise(h,SaplingSpecies.BIRCH,false)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=3800,batch="wood_dark_oak_replant")
    fun wholeDarkOakFootprintIsReplantedFromFourActualSaplingsAfterWoodDelivery(h: GameTestHelper)=exercise(h,SaplingSpecies.DARK_OAK,false)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=3000,batch="wood_replant_shortage")
    fun missingSaplingsRetainDeliveredWoodAndReportTheUnfinishedReplant(h: GameTestHelper)=exercise(h,SaplingSpecies.OAK,true)
    private fun exercise(h: GameTestHelper,species: SaplingSpecies,missing: Boolean) {
        val arena=CombatGameTestArena(h); val size=species.layoutSize; val server=h.level.server; val duration=if(size == 2) 3600 else 2800
        h.setBlock(BlockPos(0,1,4),Blocks.CHEST)
        val output=h.level.getBlockEntity(h.absolutePos(BlockPos(0,1,4))) as ChestBlockEntity
        val log=when(species) { SaplingSpecies.OAK -> Blocks.OAK_LOG; SaplingSpecies.BIRCH -> Blocks.BIRCH_LOG; SaplingSpecies.DARK_OAK -> Blocks.DARK_OAK_LOG }
        val sapling=when(species) { SaplingSpecies.OAK -> Items.OAK_SAPLING; SaplingSpecies.BIRCH -> Items.BIRCH_SAPLING; SaplingSpecies.DARK_OAK -> Items.DARK_OAK_SAPLING }
        for(dx in 0 until size) for(z in 0 until size) {
            h.setBlock(BlockPos(4+dx,0,z),Blocks.DIRT)
            for(y in 1..3) h.setBlock(BlockPos(4+dx,y,z),log)
        }
        h.setBlock(BlockPos(12,1,0),Blocks.BIRCH_LOG)
        var reloaded=false
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_AXE)); arena.give(npc,ItemStack(Items.IRON_SHOVEL)); arena.give(npc,ItemStack(Items.COARSE_DIRT,12))
            if(!missing) arena.give(npc,ItemStack(sapling,size*size+1))
            val work=PlantingWorkOrder(WorkArea(WorkBox(pos(h,4,1,0),pos(h,3+size,1,size-1))),species,PlantingMode.GAPS,keepSaplings=1)
            val planting=PlantingTaskDefinition(npc.snapshot().dimensionId,work,1,npc.snapshot().position,returnTo=npc.snapshot().position,budget=TaskBudget(duration))
            val definition=LumberjackTaskDefinition(npc.snapshot().dimensionId,WorkArea(WorkBox(pos(h,0,1,-2),pos(h,10,8,4))),
                WoodSelection(listOf(species.logId)),pos(h,0,1,4),1,budget=TaskBudget(duration),version=2,replant=planting)
            arena.assign(npc,definition)
        }
        arena.observe { npc,r ->
            val wood=checkNotNull(r.primary.lumberjack); val planting=r.primary.planting
            if(!missing && !reloaded && planting != null && planting.planted() == 1) {
                check(TaskService.pause(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                val saved=TaskStore.forServer(server).save(CompoundTag()); check(BehaviorRuntimeService.reload().accepted)
                server.overworld().dataStorage.set("samcnpc_behavior_tasks",TaskStore.load(saved)); check(TaskService.resume(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                reloaded=true
            }
            if(r.status.terminal) {
                check(r.status == if(missing) TaskStatus.FAILED else TaskStatus.COMPLETED) { TaskService.status(server,npc.npcUuid).orEmpty() }
                val s=checkNotNull(planting); val count=(0 until output.containerSize).sumOf { if(output.getItem(it).item == log.asItem()) output.getItem(it).count else 0 }
                check(count == size*size*3 && wood.resources.entries[species.logId]?.delivered == count && wood.removedWood.size == count) { "wood delivery/cut evidence differs from the real trunk: delivered=$count; removed=${wood.removedWood}" }
                check(s.planted() == if(missing) 0 else size*size)
                if(missing) check(s.stop == PlantingProblem.MISSING_SAPLINGS) else {
                    check(reloaded && TaskDelivery.inventoryCount(npc,species.blockId) == 1)
                    check(wood.resources.entries[species.blockId]?.consumed == size*size && s.resources.entries[species.blockId]?.consumed == size*size)
                }
                for(dx in 0 until size) for(z in 0 until size) {
                    val state=h.getBlockState(BlockPos(4+dx,1,z))
                    check(if(missing) state.isAir else state.block.asItem() == sapling)
                }
                check(h.getBlockState(BlockPos(12,1,0)).`is`(Blocks.BIRCH_LOG) && wood.job.pillarSession == null)
                arena.succeed(npc,r)
            }
        }
    }
    private fun pos(h: GameTestHelper,x: Int,y: Int,z: Int): NpcBlockPosition { val p=h.absolutePos(BlockPos(x,y,z)); return NpcBlockPosition(p.x,p.y,p.z) }
}
