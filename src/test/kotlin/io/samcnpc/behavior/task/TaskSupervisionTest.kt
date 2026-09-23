package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskSupervisionTest {
    private fun task() = TaskRecord.start(UUID(0, 1),
        NavigateTaskDefinition("minecraft:overworld", NpcPosition(4.5, 64.0, 0.5)), emptyList())
    private fun request(r: TaskRecord, control: OperationControl = OperationControl.PAUSE) =
        OperationControlRequest(r.id, r.controlRevision, r.amendments.revision, 100, 200, control)
    private fun root(entry: CompoundTag, version: Int = 9) = CompoundTag().apply {
        putInt("version", version); put("tasks", ListTag().apply { add(entry) })
    }

    @Test fun oldControlCannotUndoNewerResumeOrAChangedDefinition() {
        val r = task()
        r.retry(TaskReason.NO_PROGRESS, "blocked"); r.advanceTime(10)
        val old = request(r)
        assertNull(TaskControlGuard.rejection(r, old, 100))
        assertTrue(r.pause()); assertEquals(1L, r.controlRevision)
        assertFalse(r.pause()); assertEquals(1L, r.controlRevision)
        val persisted = TaskCodec.write(r)
        val loaded = TaskCodec.read(persisted)
        assertEquals(1L, loaded.controlRevision)
        assertTrue(loaded.resume()); assertEquals(2L, loaded.controlRevision)
        assertEquals(5990, loaded.primary.remainingTicks)
        assertEquals(10, loaded.primary.waitTicks); assertEquals(1, loaded.totalFailures)
        assertEquals(NpcActionCode.CONFLICT, TaskControlGuard.rejection(loaded, old, 101)?.code)
        val beforeAmendment = request(loaded)
        loaded.amendments.revision++
        assertEquals(NpcActionCode.CONFLICT, TaskControlGuard.rejection(loaded, beforeAmendment, 101)?.code)
        val before = TaskCodec.write(loaded)
        assertEquals(NpcActionCode.CONFLICT, TaskControlGuard.rejection(loaded, request(loaded).copy(taskId = UUID.randomUUID()), 101)?.code)
        assertEquals(before, TaskCodec.write(loaded))
    }

    @Test fun malformedExpiredAndFutureRequestsDoNotChangeState() {
        val r = task(); val good = request(r); val before = TaskCodec.write(r)
        for (bad in listOf(good.copy(expectedControlRevision = -1), good.copy(expectedDefinitionRevision = 33),
            good.copy(issuedTick = -1), good.copy(expiresTick = 100), good.copy(expiresTick = 1301),
            good.copy(issuedTick = Long.MAX_VALUE - 1, expiresTick = Long.MIN_VALUE))) {
            assertNotNull(TaskControlGuard.rejection(r, bad, 100))
            assertEquals(before, TaskCodec.write(r))
        }
        assertNotNull(TaskControlGuard.rejection(r, good, 99))
        assertNull(TaskControlGuard.rejection(r, good, 100))
        assertNull(TaskControlGuard.rejection(r, good, 199))
        assertNotNull(TaskControlGuard.rejection(r, good, 200))
        assertEquals(before, TaskCodec.write(r))
    }

    @Test fun everyLegacyEnvelopeMigratesAnAbsentRevisionWithoutRenewingBudget() {
        for (version in 1..8) {
            val r = task(); r.advanceTime(123); r.pause()
            val old = TaskCodec.write(r).apply {
                remove("controlRevision")
                if (version < 4) remove("reaction")
                if (version == 4) getCompound("reaction").remove("tactics")
            }
            val store = TaskStore.load(root(old, version))
            val loaded = assertNotNull(store.get(r.npcUuid))
            assertEquals(0L, loaded.controlRevision)
            assertEquals(TaskStatus.PAUSED, loaded.status)
            assertEquals(5877, loaded.primary.remainingTicks)
            assertEquals(r.id, loaded.id); assertEquals(r.primary.id, loaded.primary.id)
            val saved = store.save(CompoundTag())
            assertEquals(10, saved.getInt("version"))
            assertEquals(0L, assertNotNull(TaskStore.load(saved).get(r.npcUuid)).controlRevision)
        }
        val explicit = task(); explicit.pause(); explicit.resume()
        assertEquals(2L, TaskCodec.read(TaskCodec.write(explicit), 8).controlRevision)
    }

    @Test fun malformedCurrentRevisionIsPreservedAndCannotExecute() {
        val r = task()
        for (entry in listOf(
            TaskCodec.write(r).apply { remove("controlRevision") },
            TaskCodec.write(r).apply { putInt("controlRevision", 1) },
            TaskCodec.write(r).apply { putLong("controlRevision", -1) },
        )) {
            assertFailsWith<IllegalArgumentException> { TaskCodec.read(entry) }
            val original = root(entry); val store = TaskStore.load(original)
            assertNull(store.get(r.npcUuid)); assertNotNull(store.problemFor(r.npcUuid))
            assertEquals(original, store.save(CompoundTag()))
        }
    }

    @Test fun exhaustedRevisionStillAllowsAnIrreversibleCancelWithoutOverflow() {
        val initial = task()
        val r = TaskRecord(initial.npcUuid, initial.id, initial.frames, emptyList(), controlRevision = Long.MAX_VALUE)
        assertFalse(r.pause()); assertEquals(TaskStatus.RUNNING, r.status)
        r.finish(TaskStatus.CANCELLED, TaskReason.USER_CANCELLED, "cancel")
        assertEquals(Long.MAX_VALUE, r.controlRevision)
        assertFalse(r.resume()); assertFalse(r.pause())
        val request = request(r, OperationControl.CANCEL)
        assertNotNull(TaskControlGuard.rejection(r, request, 100))
        assertEquals(Long.MAX_VALUE, TaskCodec.read(TaskCodec.write(r)).controlRevision)
    }

    @Test fun observationsCopyBoundedFramesAndDoNotChangeAfterTaskProgress() {
        val r = task()
        assertNull(r.interrupt(NavigateTaskDefinition("minecraft:overworld", NpcPosition(2.5, 64.0, 0.5))))
        val view = TaskSupervision.snapshot(r)
        assertEquals(2, view.frames.size)
        assertEquals(r.primary.id, view.frames.first().frameId)
        assertEquals(r.active.id, view.frames.last().frameId)
        assertFailsWith<UnsupportedOperationException> { (view.frames as MutableList<*>).clear() }
        r.advanceTime(25); r.pause()
        assertEquals(6000, view.frames.first().remainingTicks)
        assertEquals(0L, view.controlRevision); assertEquals(OperationTaskState.RUNNING, view.state)
        val fresh = TaskSupervision.snapshot(r)
        assertEquals(5975, fresh.frames.first().remainingTicks)
        assertEquals(1L, fresh.controlRevision); assertEquals(OperationTaskState.PAUSED, fresh.state)
    }
}
