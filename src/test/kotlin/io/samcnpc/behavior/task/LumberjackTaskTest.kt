package io.samcnpc.behavior.task

import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class LumberjackTaskTest {
    private val npcId = UUID(0, 1)
    private val chest = NpcBlockPosition(0, 61, 0)
    private val definition = LumberjackTaskDefinition("minecraft:overworld",
        WorkArea(WorkBox(NpcBlockPosition(0, 60, 0), NpcBlockPosition(9, 80, 9)),
            listOf(WorkBox(NpcBlockPosition(5, 60, 5), NpcBlockPosition(6, 80, 6)))),
        WoodSelection(listOf("samcnpc:oak")), chest, 5)

    @Test fun exactIntentAndConfirmedEffectsRoundTripWithoutLiveControls() {
        val record = task()
        val state = checkNotNull(record.primary.lumberjack)
        state.observedRemovedBlocks.add(NpcBlockPosition(2, 61, 2))
        state.pendingBreak = WorkBlockCheckpoint(NpcBlockPosition(2, 62, 2), "minecraft:oak_log")
        state.job.scanCursor = 22
        state.job.targetPosition = state.pendingBreak?.position
        record.advanceTime(123)
        record.pause()
        val saved = TaskCodec.write(record)
        val restored = TaskCodec.read(saved)
        assertEquals(saved, TaskCodec.write(restored))
        assertEquals(5877, restored.primary.remainingTicks)
        assertTrue(checkNotNull(restored.primary.lumberjack).reconcileWorld)
        assertTrue(checkNotNull(restored.primary.lumberjack).resources.mustReconcileLoad)
        assertEquals(state.observedRemovedBlocks, restored.primary.lumberjack?.observedRemovedBlocks)
        for (forbidden in listOf("actionId", "navigationId", "observedLoadGeneration", "climbForwardTicks")) assertFalse(saved.toString().contains(forbidden))
    }

    @Test fun forgedCompletionAndLossyEmbeddedWorkCannotBecomeRunnable() {
        val original = TaskCodec.write(task())
        val completed = original.copy().apply { putString("status", "COMPLETED"); putString("reason", "DELIVERED") }
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(completed) }
        val lossy = original.copy()
        work(lossy).getCompound("work").getList("jobs", 10).getCompound(0).putInt("scanCursor", -100)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(lossy) }
        val outside = original.copy()
        work(outside).put("removed", ListTag().apply { add(CompoundTag().apply { putInt("x", 50); putInt("y", 60); putInt("z", 50) }) })
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(outside) }
    }

    @Test fun preV3CannotInventWoodTasksAndUnarchivedResidueBlocksReplacement() {
        val record = task()
        val old = root(record).apply { putInt("version", 2) }
        val rejected = TaskStore.load(old)
        assertNull(rejected.get(npcId)); assertEquals(old, rejected.save(CompoundTag()))
        record.finish(TaskStatus.CANCELLED, TaskReason.USER_CANCELLED, "cancelled with work retained")
        val store = TaskStore.load(root(record))
        assertEquals(NpcActionStatus.REJECTED, store.put(task()).status)
        checkNotNull(store.get(npcId)?.primary?.lumberjack).residuePreserved = true
        assertEquals(NpcActionStatus.SUCCEEDED, store.put(task()).status)
    }

    @Test fun mutationFenceRejectsWrongSpeciesExcludedCellsAndOtherContainersBeforeCore() {
        val state = checkNotNull(task().primary.lumberjack)
        val world = BlocksView()
        val body = Body()
        val target = NpcBlockPosition(2, 61, 2)
        world.blocks[target] = "minecraft:oak_log"
        val guard = LumberjackTaskGuard(body, world, definition, state)
        assertEquals(NpcActionStatus.SUCCEEDED, guard.startBlockBreak(target).status)
        assertEquals(1, body.breaks)
        world.blocks[target] = "minecraft:birch_log"
        assertEquals(NpcActionStatus.REJECTED, guard.startBlockBreak(target).status)
        val excluded = NpcBlockPosition(5, 61, 5); world.blocks[excluded] = "minecraft:oak_log"
        assertEquals(NpcActionStatus.REJECTED, guard.startBlockBreak(excluded).status)
        assertEquals(NpcActionStatus.REJECTED, guard.placeHeldBlock(NpcBlockPlacement(excluded), NpcHand.MAIN).status)
        assertEquals(NpcActionStatus.REJECTED, guard.moveInventoryToBlockContainer(0, NpcBlockContainerSlot(target, 0), 1).status)
        assertEquals(NpcActionStatus.REJECTED, guard.moveBlockContainerToInventory(NpcBlockContainerSlot(target, 0), 1).status)
        assertEquals(1, body.breaks); assertEquals(0, body.placements)
    }

    @Test fun reloadedRemovedBlocksAreObservedInBoundedChunksAndNeverRebroken() {
        val state = checkNotNull(task().primary.lumberjack)
        for (x in 0..9) for (y in 60..79) state.observedRemovedBlocks.add(NpcBlockPosition(x, y, 0))
        val world = BlocksView()
        assertEquals(LumberjackWorkReconciliation.Result.Pending, LumberjackWorkReconciliation.resume(state, world))
        assertEquals(128, world.reads)
        assertEquals(LumberjackWorkReconciliation.Result.Ready, LumberjackWorkReconciliation.resume(state, world))
        assertEquals(200, world.reads)
        val replaced = NpcBlockPosition(2, 61, 0); world.blocks[replaced] = "minecraft:oak_log"
        val body = Body()
        val guard = LumberjackTaskGuard(body, world, definition, state)
        assertEquals(NpcActionStatus.REJECTED, guard.startBlockBreak(replaced).status)
        assertEquals(0, body.breaks)
        state.reconciliationCursor = 0
        assertIs<LumberjackWorkReconciliation.Result.Mismatch>(LumberjackWorkReconciliation.resume(state, world))
    }

    private fun task(): TaskRecord {
        val job = LumberjackDemoJob(npcId, definition.dimensionId, chest, chest, emptyList(), LumberjackDemoPhase.SEARCH_WOOD,
            0, 0, false, null, null, null, null, mutableListOf(), false, 0, 0, false, false, 0, false, emptyMap())
        return TaskRecord.start(npcId, definition, listOf("samcnpc:idle_look")).apply {
            primary.lumberjack = LumberjackTaskState(job, HarvestResources(emptyMap()), emptyMap(), 27)
        }
    }
    private fun work(tag: CompoundTag) = tag.getList("frames", 10).getCompound(0).getCompound("lumberjack")
    private fun root(record: TaskRecord) = CompoundTag().apply { putInt("version", 3); put("tasks", ListTag().apply { add(TaskCodec.write(record)) }) }
    private class Body : TestNpcFacade() {
        var breaks = 0; var placements = 0
        override fun startBlockBreak(position: NpcBlockPosition): NpcActionResult { breaks++; return NpcActionResult.succeeded("observed start") }
        override fun placeHeldBlock(placement: NpcBlockPlacement, hand: NpcHand): NpcActionResult { placements++; return NpcActionResult.succeeded("observed placement") }
    }
    private class BlocksView : NpcWorldView {
        override val dimensionId = "minecraft:overworld"
        val blocks = mutableMapOf<NpcBlockPosition, String>()
        var reads = 0
        override fun observeBlock(position: NpcBlockPosition): NpcBlockObservation {
            reads++
            val id = blocks[position] ?: "minecraft:air"
            return NpcBlockObservation(position, id, id == "minecraft:air", id != "minecraft:air", false)
        }
        override fun observeBlockContainer(position: NpcBlockPosition): NpcBlockContainerObservation? = error("unexpected container read")
        override fun observeEntity(uuid: UUID): NpcEntityObservation? = error("unexpected entity read")
        override fun queryEntities(query: NpcEntityQuery): List<NpcEntityObservation> = error("unexpected entity scan")
        override fun raycast(request: NpcRaycastRequest): NpcRaycastResult = error("unexpected raycast")
    }
}
