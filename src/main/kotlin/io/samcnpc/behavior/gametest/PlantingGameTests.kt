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
object PlantingGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1400,batch="plant_oak_patch")
    fun oakPatchConsumesTwoActualSaplingsAndHonorsSpacingAndRetainedReserve(h: GameTestHelper)=patch(h,SaplingSpecies.OAK)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1400,batch="plant_birch_patch")
    fun birchPatchUsesTheSameRealPlacementPathWithItsOwnSpecies(h: GameTestHelper)=patch(h,SaplingSpecies.BIRCH)
    private fun patch(h: GameTestHelper,species: SaplingSpecies) {
        val arena=CombatGameTestArena(h); soil(h,species,4); soil(h,species,10)
        h.setBlock(BlockPos(18,1,0),Blocks.OAK_LOG)
        arena.onReady { npc -> arena.give(npc,ItemStack(item(species),4)); arena.assign(npc,definition(h,npc,species,PlantingMode.PATCH,2,10)) }
        arena.observe { npc,r -> if(r.status.terminal) {
            val s=checkNotNull(r.primary.planting)
            check(r.status == TaskStatus.COMPLETED && s.planted() == 2 && s.completed(r.primary.definition as PlantingTaskDefinition) == 2) { status(h,npc) }
            for(x in listOf(4,10)) check(h.getBlockState(BlockPos(x,1,0)).`is`(block(species)))
            check(TaskDelivery.inventoryCount(npc,species.blockId) == 2 && s.resources.entries[species.blockId]?.consumed == 2)
            check(h.getBlockState(BlockPos(18,1,0)).`is`(Blocks.OAK_LOG)); arena.succeed(npc,r)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1600,batch="plant_dark_oak_reload")
    fun darkOakQuadReloadResumesOnlyMissingCellsAndConsumesExactlyFourSaplings(h: GameTestHelper) {
        val species=SaplingSpecies.DARK_OAK; val arena=CombatGameTestArena(h); soil(h,species,4); var reloaded=false
        arena.onReady { npc -> arena.give(npc,ItemStack(item(species),5)); arena.assign(npc,definition(h,npc,species,PlantingMode.PATCH)) }
        arena.observe { npc,r ->
            val s=checkNotNull(r.primary.planting)
            if(!reloaded && s.planted() == 2) { reload(h,npc); reloaded=true }
            if(r.status.terminal) {
                check(r.status == TaskStatus.COMPLETED && reloaded && s.planted() == 4 && s.plots.values.single().initial.isEmpty()) { status(h,npc) }
                check(TaskDelivery.inventoryCount(npc,species.blockId) == 1 && s.resources.entries[species.blockId]?.consumed == 4)
                assertLayout(h,species,4); arena.succeed(npc,r)
            }
        }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1200,batch="plant_dark_oak_gaps")
    fun darkOakGapFillingKeepsTwoExistingSaplingsAndConsumesOnlyTwoNewMembers(h: GameTestHelper) {
        val species=SaplingSpecies.DARK_OAK; val arena=CombatGameTestArena(h); soil(h,species,4)
        h.setBlock(BlockPos(4,1,0),Blocks.DARK_OAK_SAPLING); h.setBlock(BlockPos(5,1,1),Blocks.DARK_OAK_SAPLING)
        arena.onReady { npc -> arena.give(npc,ItemStack(item(species),3)); arena.assign(npc,definition(h,npc,species,PlantingMode.GAPS)) }
        arena.observe { npc,r -> if(r.status.terminal) {
            val s=checkNotNull(r.primary.planting)
            check(r.status == TaskStatus.COMPLETED && s.planted() == 2 && s.plots.values.single().initial.size == 2) { status(h,npc) }
            assertLayout(h,species,4); check(TaskDelivery.inventoryCount(npc,species.blockId) == 1 && s.resources.entries[species.blockId]?.consumed == 2)
            arena.succeed(npc,r)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1600,batch="plant_birch_gaps")
    fun birchGapSearchDistinguishesExistingSaplingsGrownTrunksAndEligibleEmptySites(h: GameTestHelper) {
        val species=SaplingSpecies.BIRCH; val arena=CombatGameTestArena(h)
        for(x in listOf(4,10,16)) soil(h,species,x)
        h.setBlock(BlockPos(4,1,0),Blocks.BIRCH_SAPLING); h.setBlock(BlockPos(10,1,0),Blocks.BIRCH_LOG); h.setBlock(BlockPos(22,1,0),Blocks.BEDROCK)
        arena.onReady { npc -> arena.give(npc,ItemStack(item(species),2)); arena.assign(npc,definition(h,npc,species,PlantingMode.GAPS,1,22)) }
        arena.observe { npc,r -> if(r.status.terminal) {
            val s=checkNotNull(r.primary.planting)
            check(r.status == TaskStatus.COMPLETED && s.planted() == 1 && s.skipped.values.toSet() == setOf(PlantingProblem.ALREADY_PLANTED,PlantingProblem.GROWN_TREE)) { status(h,npc) }
            for(x in listOf(4,16)) check(h.getBlockState(BlockPos(x,1,0)).`is`(Blocks.BIRCH_SAPLING))
            check(h.getBlockState(BlockPos(10,1,0)).`is`(Blocks.BIRCH_LOG) && h.getBlockState(BlockPos(22,1,0)).`is`(Blocks.BEDROCK))
            check(TaskDelivery.inventoryCount(npc,species.blockId) == 1); arena.succeed(npc,r)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=800,batch="plant_quad_shortage")
    fun insufficientDarkOakStockDoesNotStartAnUnfundedQuadOrCreateSaplings(h: GameTestHelper) {
        val species=SaplingSpecies.DARK_OAK; val arena=CombatGameTestArena(h); soil(h,species,4)
        arena.onReady { npc -> arena.give(npc,ItemStack(item(species),3)); arena.assign(npc,definition(h,npc,species,PlantingMode.PATCH,keep=0).copy(budget=TaskBudget(650))) }
        arena.observe { npc,r -> if(r.status.terminal) {
            val s=checkNotNull(r.primary.planting)
            check(r.status == TaskStatus.FAILED && s.stop == PlantingProblem.MISSING_SAPLINGS && s.planted() == 0) { status(h,npc) }
            for(x in 4..5) for(z in 0..1) check(h.getBlockState(BlockPos(x,1,z)).isAir)
            check(TaskDelivery.inventoryCount(npc,species.blockId) == 3); arena.succeed(npc,r)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=800,batch="plant_invalid_sites")
    fun invalidSoilAndBlockedClearanceRemainUntouchedWithSpecificSkipReasons(h: GameTestHelper) {
        val species=SaplingSpecies.OAK; val arena=CombatGameTestArena(h); soil(h,species,10); h.setBlock(BlockPos(10,3,0),Blocks.STONE)
        arena.onReady { npc -> arena.give(npc,ItemStack(item(species),2)); arena.assign(npc,definition(h,npc,species,PlantingMode.PATCH,1,10).copy(budget=TaskBudget(650))) }
        arena.observe { npc,r -> if(r.status.terminal) {
            val s=checkNotNull(r.primary.planting)
            check(r.status == TaskStatus.FAILED && s.planted() == 0 && s.skipped.values.toSet() == setOf(PlantingProblem.INVALID_SOIL,PlantingProblem.OBSTRUCTED)) { status(h,npc) }
            check(h.getBlockState(BlockPos(4,0,0)).`is`(Blocks.STONE) && h.getBlockState(BlockPos(10,3,0)).`is`(Blocks.STONE))
            check(TaskDelivery.inventoryCount(npc,species.blockId) == 2); arena.succeed(npc,r)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2300,batch="plant_command_supply")
    fun realTreeCommandUsesReservedSourceAndQuantityAmendmentWithoutResettingItsTask(h: GameTestHelper) {
        val actor=GameTestActor(h.level,"TreeAmend"); val arena=CombatGameTestArena(h,actor.player)
        soil(h,SaplingSpecies.OAK,4); soil(h,SaplingSpecies.OAK,10); h.setBlock(BlockPos(0,1,4),Blocks.CHEST)
        val source=h.level.getBlockEntity(h.absolutePos(BlockPos(0,1,4))) as ChestBlockEntity
        source.setItem(0,ItemStack(Items.OAK_SAPLING,10)); source.setChanged()
        var amended=false; val server=h.level.server
        fun xyz(p: NpcBlockPosition)="${p.x} ${p.y} ${p.z}"
        arena.onReady { _ ->
            val p=pos(h,0,1,4); val command="samcnpc behavior task assign AmendProof plant_trees patch oak ${xyz(pos(h,4,1,0))} ${xyz(pos(h,10,1,0))} 6 1 1 2200 \"${p.x},${p.y},${p.z}\" 2"
            check(server.commands.performPrefixedCommand(actor.player.createCommandSourceStack(),command) == 1) { "real planting command rejected" }
        }
        arena.observe { npc,r ->
            val s=checkNotNull(r.primary.planting)
            if(!amended && s.planted() == 1 && s.selected == null) {
                check(TaskAmendments.automatic(server,npc,actor.player,TaskChange.Quantity(2,QuantityChangeMode.TOTAL)).status == NpcActionStatus.SUCCEEDED)
                amended=true
            }
            if(r.status.terminal) {
                check(r.status == TaskStatus.COMPLETED && amended && r.amendments.revision == 1 && s.planted() == 2) { status(h,npc) }
                check(r.logistics.outcomes.isNotEmpty() && r.logistics.outcomes.all { it.returned })
                check(source.getItem(0).count == 7 && s.resources.entries[SaplingSpecies.OAK.blockId]?.supplied == 3 && TaskDelivery.inventoryCount(npc,SaplingSpecies.OAK.blockId) == 1)
                actor.close(); arena.succeed(npc,r)
            }
        }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=900,batch="plant_quad_cancel")
    fun cancellingHalfAQuadPreservesItsPhysicalMembersAndStopsFurtherConsumption(h: GameTestHelper) {
        val arena=CombatGameTestArena(h); soil(h,SaplingSpecies.DARK_OAK,4); var cancelled=false; var checked=false
        arena.onReady { npc -> arena.give(npc,ItemStack(Items.DARK_OAK_SAPLING,5)); arena.assign(npc,definition(h,npc,SaplingSpecies.DARK_OAK,PlantingMode.PATCH)) }
        arena.observe { npc,r ->
            val s=checkNotNull(r.primary.planting)
            if(!cancelled && s.planted() == 2) { check(TaskService.cancel(h.level.server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED); cancelled=true }
            if(r.status.terminal && !checked) {
                checked=true; check(cancelled && r.status == TaskStatus.CANCELLED) { status(h,npc) }
                h.runAfterDelay(10) {
                    check(s.planted() == 2 && TaskDelivery.inventoryCount(npc,SaplingSpecies.DARK_OAK.blockId) == 3)
                    check((4..5).sumOf { x -> (0..1).count { z -> h.getBlockState(BlockPos(x,1,z)).`is`(Blocks.DARK_OAK_SAPLING) } } == 2)
                    arena.succeed(npc,r)
                }
            }
        }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1500,batch="plant_changed_reload")
    fun restoredPlantingRejectsAForeignReplacementWithoutSpendingAnotherSapling(h: GameTestHelper)=changed(h,false)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1500,batch="plant_grown_reload")
    fun restoredOakGapsAcceptMatchingGrownTrunkAsTheOldEffectAndContinueNewSites(h: GameTestHelper)=changed(h,true)
    private fun changed(h: GameTestHelper,grown: Boolean) {
        val arena=CombatGameTestArena(h); soil(h,SaplingSpecies.OAK,4); soil(h,SaplingSpecies.OAK,10); var reloaded=false
        arena.onReady { npc -> arena.give(npc,ItemStack(Items.OAK_SAPLING,3)); arena.assign(npc,definition(h,npc,SaplingSpecies.OAK,PlantingMode.GAPS,2,10)) }
        arena.observe { npc,r ->
            val s=checkNotNull(r.primary.planting)
            if(!reloaded && s.planted() == 1 && s.selected == null) {
                h.setBlock(BlockPos(4,1,0),if(grown) Blocks.OAK_LOG else Blocks.STONE)
                reload(h,npc); reloaded=true
            }
            if(r.status.terminal) {
                check(reloaded && r.status == if(grown) TaskStatus.COMPLETED else TaskStatus.FAILED) { status(h,npc) }
                check(s.planted() == if(grown) 2 else 1)
                check(h.getBlockState(BlockPos(4,1,0)).`is`(if(grown) Blocks.OAK_LOG else Blocks.STONE))
                if(!grown) check(s.stop == PlantingProblem.CHANGED && h.getBlockState(BlockPos(10,1,0)).isAir)
                check(TaskDelivery.inventoryCount(npc,SaplingSpecies.OAK.blockId) == if(grown) 1 else 2)
                arena.succeed(npc,r)
            }
        }
    }
    private fun reload(h: GameTestHelper,npc: NpcFacade) {
        val server=h.level.server; check(TaskService.pause(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
        val saved=TaskStore.forServer(server).save(CompoundTag()); check(BehaviorRuntimeService.reload().accepted)
        server.overworld().dataStorage.set("samcnpc_behavior_tasks",TaskStore.load(saved))
        check(TaskService.resume(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
    }
    private fun definition(h: GameTestHelper,npc: NpcFacade,species: SaplingSpecies,mode: PlantingMode,quantity: Int=1,maxX: Int=4,keep: Int=1)=
        PlantingTaskDefinition(npc.snapshot().dimensionId,PlantingWorkOrder(WorkArea(WorkBox(pos(h,4,1,0),pos(h,maxX+species.layoutSize-1,1,species.layoutSize-1))),species,mode,keepSaplings=keep),quantity,npc.snapshot().position,returnTo=npc.snapshot().position,budget=TaskBudget(1300))
    private fun soil(h: GameTestHelper,species: SaplingSpecies,x: Int) { for(dx in 0 until species.layoutSize) for(z in 0 until species.layoutSize) h.setBlock(BlockPos(x+dx,0,z),Blocks.DIRT) }
    private fun assertLayout(h: GameTestHelper,species: SaplingSpecies,x: Int) { for(dx in 0 until species.layoutSize) for(z in 0 until species.layoutSize) check(h.getBlockState(BlockPos(x+dx,1,z)).`is`(block(species))) }
    private fun item(s: SaplingSpecies)=when(s) { SaplingSpecies.OAK -> Items.OAK_SAPLING; SaplingSpecies.BIRCH -> Items.BIRCH_SAPLING; SaplingSpecies.DARK_OAK -> Items.DARK_OAK_SAPLING }
    private fun block(s: SaplingSpecies)=when(s) { SaplingSpecies.OAK -> Blocks.OAK_SAPLING; SaplingSpecies.BIRCH -> Blocks.BIRCH_SAPLING; SaplingSpecies.DARK_OAK -> Blocks.DARK_OAK_SAPLING }
    private fun pos(h: GameTestHelper,x: Int,y: Int,z: Int): NpcBlockPosition { val p=h.absolutePos(BlockPos(x,y,z)); return NpcBlockPosition(p.x,p.y,p.z) }
    private fun status(h: GameTestHelper,npc: NpcFacade)=TaskService.status(h.level.server,npc.npcUuid).orEmpty()
}
