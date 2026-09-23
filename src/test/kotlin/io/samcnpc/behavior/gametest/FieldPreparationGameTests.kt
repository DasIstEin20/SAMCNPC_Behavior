package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FarmBlock
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder("samcnpc_field_zoo")
@PrefixGameTestTemplate(false)
object FieldPreparationGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2400,batch="field_prepare")
    fun hoeOnlyPreparesNineSoilCellsAndReturnsWithoutSeeds(helper: GameTestHelper)=scene(helper,false)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2400,batch="field_prepare_reload")
    fun preparedCellsAndFiniteUseAllowancesSurvivePauseAndReload(helper: GameTestHelper)=scene(helper,true)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2400,batch="field_prepare_changed")
    fun explicitResumeRechecksChangedSoilBeforeContinuing(helper: GameTestHelper)=scene(helper,false,true)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=800,batch="field_missing_hoe")
    fun noHoeFailsWithoutMakingFarmland(helper: GameTestHelper)=rejection(helper,FieldProblem.MISSING_HOE)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=800,batch="field_blocked")
    fun coveredSoilDoesNotAuthorizeClearing(helper: GameTestHelper)=rejection(helper,FieldProblem.BLOCKED)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=800,batch="field_invalid_soil")
    fun unsupportedSoilDoesNotAuthorizeReplacement(helper: GameTestHelper)=rejection(helper,FieldProblem.INVALID_SOIL)

    private fun scene(h: GameTestHelper,reload: Boolean,changed: Boolean=false) {
        val arena=CombatGameTestArena(h);val server=h.level.server
        for(x in 3..6) for(z in 0..2) h.setBlock(BlockPos(x,0,z),Blocks.DIRT)
        h.setBlock(BlockPos(7,0,1),Blocks.WATER)
        h.setBlock(BlockPos(4,0,0),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7))
        val area=WorkArea(WorkBox(block(h,4,0,0),block(h,6,0,2)))
        var reloaded=false
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_HOE))
            arena.assign(npc,PrepareFieldTaskDefinition(npc.snapshot().dimensionId,area,arena.start,returnTo=arena.start,budget=TaskBudget(2200)))
        }
        arena.observe { npc,r ->
            val state=checkNotNull(r.primary.fieldPreparation)
            if((reload || changed) && !reloaded && state.confirmed.size>=3 && !r.status.terminal) {
                check(TaskService.pause(server,npc.npcUuid).status==NpcActionStatus.SUCCEEDED)
                if(reload) {
                    val saved=TaskStore.forServer(server).save(CompoundTag())
                    check(BehaviorRuntimeService.reload().accepted)
                    server.overworld().dataStorage.set("samcnpc_behavior_tasks",TaskStore.load(saved))
                }
                if(changed) h.setBlock(BlockPos(5,0,0),Blocks.DIRT)
                check(TaskService.resume(server,npc.npcUuid).status==NpcActionStatus.SUCCEEDED);reloaded=true
            }
            if(r.status.terminal) {
                check(r.status==TaskStatus.COMPLETED && r.reason==TaskReason.FIELD_PREPARED) { TaskService.status(server,npc.npcUuid).orEmpty() }
                val expectedUses=if(changed) 9 else 8
                check(!(reload || changed) || reloaded);check(state.confirmed.size==9 && state.attempts.values.sum()==expectedUses)
                if(changed) check(state.attempts[block(h,5,0,0)]==2)
                val carried=npc.inventoryContents().filter { !it.stack.isEmpty }
                check(carried.size==1 && carried.single().stack.itemId=="minecraft:iron_hoe" && carried.single().stack.damage==expectedUses)
                for(x in 4..6) for(z in 0..2) {
                    check(h.getBlockState(BlockPos(x,0,z)).`is`(Blocks.FARMLAND));check(h.getBlockState(BlockPos(x,1,z)).isAir)
                }
                check(h.getBlockState(BlockPos(3,0,0)).`is`(Blocks.DIRT))
                check(TaskNavigator.distanceSquared(npc.snapshot().position,arena.start)<=0.75*0.75)
                arena.succeed(npc,r)
            }
        }
    }
    private fun rejection(h: GameTestHelper,expected: FieldProblem) {
        val arena=CombatGameTestArena(h)
        h.setBlock(BlockPos(4,0,0),if(expected==FieldProblem.INVALID_SOIL) Blocks.STONE else Blocks.DIRT)
        if(expected==FieldProblem.BLOCKED) h.setBlock(BlockPos(4,1,0),Blocks.STONE)
        val original=h.getBlockState(BlockPos(4,0,0))
        val position=block(h,4,0,0)
        arena.onReady { npc ->
            if(expected!=FieldProblem.MISSING_HOE) arena.give(npc,ItemStack(Items.IRON_HOE))
            arena.assign(npc,PrepareFieldTaskDefinition(npc.snapshot().dimensionId,WorkArea(WorkBox(position,position)),arena.start,returnTo=arena.start))
        }
        arena.observe { npc,r -> if(r.status.terminal) {
            check(r.status==TaskStatus.FAILED && r.primary.fieldPreparation?.stop==expected) { r.report().toString() }
            check(h.getBlockState(BlockPos(4,0,0))==original)
            if(expected==FieldProblem.BLOCKED) check(h.getBlockState(BlockPos(4,1,0)).`is`(Blocks.STONE))
            check(npc.inventoryContents().filter { !it.stack.isEmpty }.all { it.stack.damage==0 })
            arena.succeed(npc,r)
        } }
    }
    private fun block(h: GameTestHelper,x: Int,y: Int,z: Int): NpcBlockPosition {
        val p=h.absolutePos(BlockPos(x,y,z));return NpcBlockPosition(p.x,p.y,p.z)
    }
}
