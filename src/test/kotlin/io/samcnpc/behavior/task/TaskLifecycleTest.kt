package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcPosition
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskLifecycleTest {
    @Test
    fun pausePreservesWaitDeadlineAndDoesNotGrantFreshAttempts() {
        val record = task()
        record.retry(TaskReason.NO_PROGRESS, "blocked")
        assertEquals(20, record.active.waitTicks)
        assertTrue(record.pause())
        val remaining = record.primary.remainingTicks
        record.advanceTime(1000)
        assertEquals(remaining, record.primary.remainingTicks)
        assertTrue(record.resume())
        assertEquals(TaskStatus.WAITING, record.status)
        record.advanceTime(20)
        assertEquals(TaskStatus.RUNNING, record.status)
        record.retry(TaskReason.NO_PROGRESS, "still blocked")
        assertEquals(40, record.active.waitTicks)
        record.advanceTime(40)
        record.retry(TaskReason.NO_PROGRESS, "blocked again")
        assertEquals(TaskStatus.FAILED, record.status)
        assertEquals(TaskReason.RETRY_LIMIT, record.reason)
        assertEquals(3, record.report().failures)
        assertFalse(record.resume())
    }

    @Test
    fun twoInterruptionsReturnToTheirOwnDestinationsWithoutRefreshingSharedTime() {
        val record = task()
        val primaryId = record.primary.id
        assertNull(record.interrupt(definition(2.0)))
        val first = record.active.id
        assertNull(record.interrupt(definition(3.0)))
        assertNotNull(record.interrupt(definition(4.0)))
        assertEquals(3, record.frames.size)
        record.advanceTime(30)
        assertTrue(record.frames.all { it.remainingTicks == 570 })
        record.completeActive()
        assertEquals(first, record.active.id)
        record.completeActive()
        assertEquals(primaryId, record.active.id)
        assertEquals(570, record.primary.remainingTicks)
        assertEquals(2, record.report().completedInterruptions)
        record.completeActive()
        assertEquals(TaskStatus.COMPLETED, record.status)
    }

    @Test
    fun waitingAndInterruptedWorkHaveAHardOverallDeadline() {
        val record = task()
        record.retry(TaskReason.DESTINATION_UNAVAILABLE, "unloaded")
        assertNull(record.interrupt(definition(2.0)))
        record.advanceTime(600)
        assertEquals(TaskStatus.FAILED, record.status)
        assertEquals(TaskReason.TIME_LIMIT, record.reason)
        assertEquals(0, record.primary.remainingTicks)
    }

    @Test
    fun cancelRetainsObservedProgressAndCannotBeRelabelledByLateCompletion() {
        val record = task()
        record.reconciledPosition = NpcPosition(0.5, 64.0, 0.0)
        record.advanceTime(80)
        record.finish(TaskStatus.CANCELLED, TaskReason.USER_CANCELLED, "cancelled")
        val final = record.report()
        record.completeActive()
        record.finish(TaskStatus.COMPLETED, TaskReason.ARRIVED, "late")
        assertEquals(final, record.report())
        assertEquals(NpcPosition(0.5, 64.0, 0.0), record.reconciledPosition)
        assertEquals(520, final.remainingTicks)
    }

    @Test
    fun definitionAndPreviousAssignmentValidationAreBoundedAndCopied() {
        assertNotNull(definition().copy(destination = NpcPosition(Double.NaN, 64.0, 0.0)).validationProblem())
        assertNotNull(definition().copy(version = 2).validationProblem())
        assertNotNull(definition().copy(budget = TaskBudget(attempts = 100)).validationProblem())
        val packs = mutableListOf("test:previous")
        val record = TaskRecord.start(UUID(0, 1), definition(), packs)
        packs.clear()
        assertEquals(listOf("test:previous"), record.previousPacks)
    }

    private fun definition(x: Double = 1.0) = NavigateTaskDefinition("minecraft:overworld", NpcPosition(x, 64.0, 0.0), budget = TaskBudget(ticks = 600))
    private fun task() = TaskRecord.start(UUID(0, 1), definition(), emptyList())
}
