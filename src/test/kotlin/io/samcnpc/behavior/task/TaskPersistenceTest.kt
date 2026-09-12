package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskPersistenceTest {
    private val npc = UUID(0, 1)
    private fun task() = TaskRecord.start(npc, NavigateTaskDefinition("minecraft:overworld", NpcPosition(4.5, 64.0, 0.5)), listOf("test:prior"))

    @Test
    fun pausedNestedWorkRoundTripsItsIntentAndConsumedBudgetsWithoutCoreHandles() {
        val record = task()
        record.retry(TaskReason.NO_PROGRESS, "blocked")
        assertNull(record.interrupt(NavigateTaskDefinition("minecraft:overworld", NpcPosition(2.5, 64.0, 0.5))))
        record.advanceTime(50)
        record.pause()
        val tag = TaskCodec.write(record)
        val restored = TaskCodec.read(tag)
        assertEquals(record.id, restored.id)
        assertEquals(record.frames.map { it.id }, restored.frames.map { it.id })
        assertEquals(record.frames.map { it.definition }, restored.frames.map { it.definition })
        assertEquals(record.report(), restored.report())
        assertEquals(5950, restored.primary.remainingTicks)
        assertEquals(20, restored.primary.waitTicks)
        assertEquals(listOf("test:prior"), restored.previousPacks)
        for (forbidden in listOf("navigationId", "actionId", "generation", "path", "entityReference")) assertFalse(tag.toString().contains(forbidden))
        restored.resume()
        restored.completeActive()
        assertEquals(TaskStatus.WAITING, restored.status)
        restored.advanceTime(20)
        assertEquals(TaskStatus.RUNNING, restored.status)
    }

    @Test
    fun malformedOrFutureDefinitionsCannotRunAndRemainPreserved() {
        val malformed = TaskCodec.write(task()).apply { putString("status", "WAITING") }
        val future = TaskCodec.write(task())
        future.getList("frames", 10).getCompound(0).getCompound("definition").putInt("version", 99)
        val unknown = TaskCodec.write(task())
        unknown.getList("frames", 10).getCompound(0).getCompound("definition").putString("id", "unknown:operation")
        for (entry in listOf(malformed, future, unknown)) {
            val root = root(entry)
            val store = TaskStore.load(root)
            assertNull(store.get(npc))
            assertNotNull(store.problemFor(npc))
            assertEquals(root, store.save(CompoundTag()))
            assertEquals(NpcActionStatus.REJECTED, store.put(task()).status)
        }
    }

    @Test
    fun duplicateOrFutureFilesAreSafeIdleAndCannotEraseOriginalIntent() {
        for (root in listOf(root(TaskCodec.write(task()), TaskCodec.write(task())), root(TaskCodec.write(task())).apply { putInt("version", 99) })) {
            val store = TaskStore.load(root)
            assertNull(store.get(npc))
            assertNotNull(store.problemFor(npc))
            assertEquals(root, store.save(CompoundTag()))
        }
    }

    @Test
    fun onlyTerminalReportsCanBeReplacedByANewPrimaryTask() {
        val record = task()
        val store = TaskStore.load(root(TaskCodec.write(record)))
        assertEquals(NpcActionStatus.REJECTED, store.put(task()).status)
        val current = assertNotNull(store.get(npc))
        current.pause()
        assertEquals(NpcActionStatus.REJECTED, store.put(task()).status)
        current.finish(TaskStatus.CANCELLED, TaskReason.USER_CANCELLED, "cancelled")
        val next = task()
        assertEquals(NpcActionStatus.SUCCEEDED, store.put(next).status)
        assertEquals(next.id, store.get(npc)?.id)
        assertEquals(next.id, TaskStore.load(store.save(CompoundTag())).get(npc)?.id)
    }

    private fun root(vararg entries: CompoundTag) = CompoundTag().apply {
        putInt("version", 5)
        put("tasks", ListTag().apply { entries.forEach(::add) })
    }
}
