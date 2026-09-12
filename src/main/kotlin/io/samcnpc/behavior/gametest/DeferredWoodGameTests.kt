package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.lumberjack.LumberjackDeferredWork
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.*

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object DeferredWoodGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1700, batch = "deferred_branch_resume")
    fun savedDeferredBranchIsRevisitedAfterNpcActuallyRemovesItsRecordedObstruction(helper: GameTestHelper) {
        val server = helper.level.server; val arena = CombatGameTestArena(helper)
        arena.body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_AXE))
        for (x in listOf(6, 8)) helper.setBlock(BlockPos(x, 1, 0), Blocks.OAK_LOG)
        helper.setBlock(BlockPos(14, 1, 0), Blocks.CHEST)
        val chest = helper.level.getBlockEntity(helper.absolutePos(BlockPos(14, 1, 0))) as ChestBlockEntity
        fun position(x: Int): NpcBlockPosition { val p = helper.absolutePos(BlockPos(x, 1, 0)); return NpcBlockPosition(p.x, p.y, p.z) }
        var firstRemoved = false; var revisited = false
        arena.onReady { npc ->
            arena.assign(npc, LumberjackTaskDefinition(npc.snapshot().dimensionId, WorkArea(WorkBox(position(6), position(8))),
                WoodSelection(listOf("samcnpc:oak")), position(14), 2, version = 2, budget = TaskBudget(ticks = 1600)))
            val record = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid)); val job = checkNotNull(record.primary.lumberjack).job
            // A saved, already-deferred target is fixture state, not a claimed prior world effect.
            job.phase = LumberjackDemoPhase.SEARCH_WOOD; job.scanCursor = 2; job.targetPosition = position(6)
            LumberjackDeferredWork.record(job, npc.worldView(), position(8)); check(job.deferredWood.size == 1)
            job.targetPosition = null
            val saved = TaskStore.forServer(server).save(CompoundTag()); check(BehaviorRuntimeService.reload().accepted)
            server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(saved))
        }
        arena.observe { npc, record ->
            val job = checkNotNull(record.primary.lumberjack).job
            if (helper.getBlockState(BlockPos(8, 1, 0)).isAir) firstRemoved = true
            if (job.deferredWood.single().revisited) {
                check(firstRemoved) { "deferred target was selected before its blocker disappeared" }; revisited = true
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && firstRemoved && revisited) { TaskService.status(server, npc.npcUuid).orEmpty() }
                check(helper.getBlockState(BlockPos(6, 1, 0)).isAir)
                check((0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.OAK_LOG)) chest.getItem(it).count else 0 } == 2)
                check(job.deferredWood.single().failedAttempts == 1 && record.primary.remainingTicks < 1600)
                arena.succeed(npc, record)
            }
        }
    }
}
