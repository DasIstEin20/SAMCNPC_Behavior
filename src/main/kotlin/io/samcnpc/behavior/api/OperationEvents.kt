package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import java.util.UUID

enum class OperationEventKind {
    TASK_ASSIGNED, TASK_COMPLETED, TASK_FAILED, TASK_CANCELLED,
    CONTROL_CHANGED, DEFINITION_CHANGED, TASK_STACK_CHANGED, RETRY_OBSERVED,
    STATE_CHANGED, ACTION_FAILED;
    val decisionBoundary: Boolean get() = this == TASK_COMPLETED || this == TASK_FAILED
}

/** No raw diagnostic text: existing human diagnostics can contain a foreign reservation's coordinates. */
@ConsistentCopyVisibility
data class OperationJournalEvent internal constructor(
    val sequence: Long,
    val observedTick: Long,
    val kind: OperationEventKind,
    val taskId: UUID?,
    val objectiveId: UUID?,
    val frameId: UUID?,
    val operationId: String?,
    val taskState: OperationTaskState?,
    val taskReason: String?,
    val definitionRevision: Int?,
    val controlRevision: Long?,
    val totalTaskFailures: Int?,
    val frameFailures: Int?,
    val attemptLimit: Int?,
    val actionId: UUID?,
    val actionCode: NpcActionCode?,
    val actionChannel: NpcActionChannel?,
    val coalesced: Boolean,
)

sealed interface OperationJournalState {
    data object NotRecorded : OperationJournalState
    class Recorded internal constructor(
        val journalId: UUID,
        val coverageStartTick: Long,
        val lastSampleTick: Long,
        val oldestAvailableSequence: Long,
        val latestSequence: Long,
        val discardedEntries: Long,
        events: List<OperationJournalEvent>,
    ) : OperationJournalState {
        val events: List<OperationJournalEvent> = java.util.List.copyOf(events)
        init { require(this.events.size <= 32) }
    }
}

class OperationEventBatch internal constructor(
    val npcUuid: UUID,
    val generations: OperationGenerations,
    val journal: OperationJournalState.Recorded,
)
