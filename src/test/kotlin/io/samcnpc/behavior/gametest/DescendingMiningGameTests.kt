package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.Items
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder("samcnpc_mining_zoo")
@PrefixGameTestTemplate(false)
object DescendingMiningGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=6200,batch="mining_descent")
    fun descendsThroughStoneCollectsCoalAndReturnsOnThePreservedStairs(helper: GameTestHelper) = scene(helper,false)

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=6200,batch="mining_descent_reload")
    fun pausedDescendingTunnelResumesItsExactGeometryAndPhysicallyReturns(helper: GameTestHelper) = scene(helper,true)

    private fun scene(helper: GameTestHelper,reload: Boolean) {
        // The empty GameTest template starts near minBuildHeight; leave real vertical room.
        val floorOffset=24
        val arena=CombatGameTestArena(helper,floorOffset=floorOffset);val server=helper.level.server
        for (x in 2..19) for (z in -3..3) for (y in floorOffset-13..floorOffset) helper.setBlock(BlockPos(x,y,z),Blocks.STONE)
        val chestPosition=block(helper,-1,floorOffset+1,3)
        helper.setBlock(BlockPos(-1,floorOffset+1,3),Blocks.CHEST)
        val chest=helper.level.getBlockEntity(BlockPos(chestPosition.x,chestPosition.y,chestPosition.z)) as ChestBlockEntity
        fun clearElevatedFixture() {
            // GameTest clears its tiny template bounds, not this raised terrain. Leaving it
            // behind would shade later batches that exercise real grass/crop random ticks.
            chest.clearContent()
            for (x in -3..30) for (z in -10..12) for (y in floorOffset..floorOffset+5)
                helper.setBlock(BlockPos(x,y,z),Blocks.AIR)
            for (x in 2..19) for (z in -3..3) for (y in floorOffset-13 until floorOffset)
                helper.setBlock(BlockPos(x,y,z),Blocks.AIR)
        }
        val g=TunnelGeometry(block(helper,4,floorOffset+1,0),TunnelDirection.EAST,1,3,13,1)
        check(g.bounds().min.y-1>helper.level.minBuildHeight)
        val work=MiningWorkOrder(WorkArea(g.bounds()),MiningMethod.TUNNEL,
            WorkResourceIds(listOf("minecraft:stone","minecraft:coal_ore")),tunnel=g)
        for (index in listOf(g.volume-3,g.volume-1)) {
            val p=g.cell(index);helper.level.setBlock(BlockPos(p.x,p.y,p.z),Blocks.COAL_ORE.defaultBlockState(),3)
        }
        var lowestY=arena.start.y;var reloaded=false
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_PICKAXE));arena.give(npc,ItemStack(Items.COBBLESTONE,3));arena.give(npc,ItemStack(Items.COAL,1))
            arena.assign(npc,MiningTaskDefinition(npc.snapshot().dimensionId,work,
                WorkResourceIds(listOf("minecraft:cobblestone","minecraft:coal")),ContainerChoices(listOf(chestPosition)),
                1,MiningCounting.CLEARED_VOLUME,arena.start,returnTo=arena.start,budget=TaskBudget(6000)))
        }
        arena.observe { npc,record ->
            lowestY=minOf(lowestY,npc.snapshot().position.y)
            val state=checkNotNull(record.primary.mining)
            if (reload && !reloaded && state.phase==MiningPhase.COLLECT && state.selection.removed.size>=9) {
                check(TaskService.pause(server,npc.npcUuid).status==NpcActionStatus.SUCCEEDED)
                val saved=TaskStore.forServer(server).save(CompoundTag())
                check(BehaviorRuntimeService.reload().accepted)
                server.overworld().dataStorage.set("samcnpc_behavior_tasks",TaskStore.load(saved))
                check(TaskService.resume(server,npc.npcUuid).status==NpcActionStatus.SUCCEEDED)
                reloaded=true
            }
            if (record.status.terminal) {
                var verified=false
                try {
                check(record.status==TaskStatus.COMPLETED) { TaskService.status(server,npc.npcUuid).orEmpty() }
                check(!reload || reloaded);check(lowestY<=arena.start.y-8) { "NPC never physically descended: $lowestY" }
                check(state.selection.removed.size==33 && state.selection.cleared.size==39)
                check((0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.COBBLESTONE)) chest.getItem(it).count else 0 }==31)
                check((0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.COAL)) chest.getItem(it).count else 0 }==2)
                check(TaskDelivery.inventoryCount(npc,"minecraft:cobblestone")==3 && TaskDelivery.inventoryCount(npc,"minecraft:coal")==1)
                check(npc.inventoryContents().single { it.stack.itemId=="minecraft:iron_pickaxe" }.stack.damage==33)
                check(TaskNavigator.distanceSquared(npc.snapshot().position,arena.start)<=0.75*0.75)
                for (i in 0 until g.volume) { val p=g.cell(i);check(helper.level.getBlockState(BlockPos(p.x,p.y,p.z)).isAir) }
                for (step in 0 until g.length) {
                    val floor=g.cell(step*g.height+g.height-1)
                    check(helper.level.getBlockState(BlockPos(floor.x,floor.y-1,floor.z)).`is`(Blocks.STONE)) { "support stair was removed" }
                }
                verified=true
                } finally {
                    clearElevatedFixture()
                    if (!verified) arena.close()
                }
                arena.succeed(npc,record)
            }
        }
    }
    private fun block(helper: GameTestHelper,x: Int,y: Int,z: Int): NpcBlockPosition {
        val p=helper.absolutePos(BlockPos(x,y,z));return NpcBlockPosition(p.x,p.y,p.z)
    }
}
