package io.samcnpc.behavior.observation

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class OperationEventJournalTest {
    private val taskId = UUID(1, 1)
    private val frameId = UUID(2, 2)
    private fun task(state: OperationTaskState = OperationTaskState.RUNNING, control: Long = 0,
                     revision: Int = 1, failures: Int = 0, interruptions: Int = 0, remaining: Int = 500,
                     id: UUID = taskId) = OperationTaskSnapshot(id, UUID(3, 3), revision, control, state,
        "NONE", "PRIVATE foreign reservation at 999,64,999", failures, interruptions, null,
        listOf(OperationFrameSnapshot(frameId, "samcnpc:navigate", 1, remaining, 500, failures, 8, 0, "NONE")))

    @Test fun healthyProgressDoesNotBecomeAnEventAndTerminalFailureIsADecisionBoundary() {
        val journal = OperationEventJournal(UUID.randomUUID(), 10, task(), emptyList())
        assertTrue(journal.sample(11, task(remaining = 480), emptyList()))
        assertTrue(journal.read().events.isEmpty())
        journal.sample(12, task(state = OperationTaskState.WAITING, failures = 1), emptyList())
        assertEquals(listOf(OperationEventKind.RETRY_OBSERVED), journal.read().events.map { it.kind })
        assertFalse(journal.read().events.single().kind.decisionBoundary)
        journal.sample(13, task(state = OperationTaskState.FAILED, failures = 8), emptyList())
        assertEquals(OperationEventKind.TASK_FAILED, journal.read().events.last().kind)
        assertTrue(journal.read().events.last().kind.decisionBoundary)
        assertTrue(journal.read().events[journal.read().events.lastIndex - 1].coalesced)
        journal.sample(14, task(state = OperationTaskState.FAILED, failures = 8), emptyList())
        assertEquals(3, journal.read().events.size)
    }

    @Test fun revisionsAndCompletedInterruptionAreObservedEvenWhenFinalStateAndFramesMatch() {
        val journal = OperationEventJournal(UUID.randomUUID(), 10, task(), emptyList())
        journal.sample(11, task(control = 2, revision = 3, interruptions = 1), emptyList())
        val events = journal.read().events
        assertEquals(listOf(OperationEventKind.CONTROL_CHANGED, OperationEventKind.DEFINITION_CHANGED,
            OperationEventKind.TASK_STACK_CHANGED), events.map { it.kind })
        assertTrue(events[0].coalesced)
        assertTrue(events[1].coalesced)
        assertFalse(events.any { it.kind.decisionBoundary })
    }

    @Test fun newTaskAlreadyTerminalReportsAssignmentAndCompletionOnce() {
        val journal = OperationEventJournal(UUID.randomUUID(), 0, null, emptyList())
        val completed = task(state = OperationTaskState.COMPLETED)
        journal.sample(1, completed, emptyList())
        journal.sample(2, completed, emptyList())
        assertEquals(listOf(OperationEventKind.TASK_ASSIGNED, OperationEventKind.TASK_COMPLETED),
            journal.read().events.map { it.kind })
        journal.sample(3, task(state = OperationTaskState.CANCELLED, id = UUID(9, 9)), emptyList())
        assertEquals(OperationEventKind.TASK_CANCELLED, journal.read().events.last().kind)
        assertFalse(journal.read().events.last().kind.decisionBoundary)
    }

    @Test fun ringIsDetachedBoundedAndExposesLossAndCursor() {
        val journal = OperationEventJournal(UUID.randomUUID(), 0, task(), emptyList(), capacity = 2)
        journal.sample(1, task(control = 1), emptyList())
        val first = journal.read()
        for (revision in 2L..5L) journal.sample(revision, task(control = revision), emptyList())
        val latest = journal.read()
        assertEquals(1, first.events.size)
        assertEquals(1L, first.latestSequence)
        assertEquals(3L, latest.discardedEntries)
        assertEquals(4L, latest.oldestAvailableSequence)
        assertEquals(listOf(5L), journal.read(4).events.map { it.sequence })
        assertFailsWith<UnsupportedOperationException> { (latest.events as MutableList).clear() }
    }

    @Test fun completionCodesAreDeduplicatedWithoutExportingTextOrInventingTaskCausality() {
        val old = NpcActionCompletion(NpcActionResult.failed("SECRET", NpcActionCode.NO_PROGRESS,
            UUID(4, 4), NpcActionChannel.LOCOMOTION), 5)
        val fresh = old.copy(gameTime = 11)
        val journal = OperationEventJournal(UUID.randomUUID(), 10, task(), listOf(old))
        journal.sample(11, task(), listOf(old, fresh, fresh))
        val event = journal.read().events.single()
        assertEquals(OperationEventKind.ACTION_FAILED, event.kind)
        assertEquals(NpcActionCode.NO_PROGRESS, event.actionCode)
        assertNull(event.taskId)
        assertNull(event.frameId)
        assertFalse(event.toString().contains("SECRET"))
        assertFalse(event.toString().contains("PRIVATE"))
        journal.sample(12, task(), listOf(old, fresh))
        assertEquals(1, journal.read().events.size)
    }

    @Test fun cancellationExpiryAndSuccessDoNotBecomeActionFailures() {
        val codes = listOf(NpcActionCode.CANCELLED, NpcActionCode.EXPIRED)
        val completions = codes.map { NpcActionCompletion(NpcActionResult.failed("x", it), 1) } +
            NpcActionCompletion(NpcActionResult.succeeded("ok"), 1)
        val journal = OperationEventJournal(UUID.randomUUID(), 0, null, emptyList())
        journal.sample(1, null, completions)
        assertTrue(journal.read().events.isEmpty())
    }

    @Test fun backwardClockDoesNotMutateExistingHistory() {
        val journal = OperationEventJournal(UUID.randomUUID(), 10, task(), emptyList())
        assertFalse(journal.sample(9, task(control = 1), emptyList()))
        assertEquals(10L, journal.read().lastSampleTick)
        assertEquals(0L, journal.latestSequence)
    }
}
