package io.samcnpc.behavior.observation

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionCompletion
import io.samcnpc.core.api.NpcActionStatus
import java.util.ArrayDeque
import java.util.UUID

/** Bounded sampled history. Healthy progress/countdowns are not events and never decision boundaries. */
internal class OperationEventJournal(
    private val id: UUID,
    private val coverageStartTick: Long,
    initialTask: OperationTaskSnapshot?,
    initialCompletions: List<NpcActionCompletion>,
    private val capacity: Int = 32,
) {
    private data class CompletionKey(val id: UUID?, val tick: Long, val code: NpcActionCode,
                                     val status: NpcActionStatus, val channel: io.samcnpc.core.api.NpcActionChannel?)
    private val entries = ArrayDeque<OperationJournalEvent>()
    private var previous = initialTask
    private var seenCompletions = initialCompletions.takeLast(16).map(::key).toSet()
    private var sequence = 0L
    private var discarded = 0L
    private var lastTick = coverageStartTick
    val latestSequence: Long get() = sequence
    init { require(coverageStartTick >= 0 && capacity in 1..32) }

    /** False closes this journal rather than wrapping sequence numbers or mixing clock epochs. */
    fun sample(tick: Long, task: OperationTaskSnapshot?, completions: List<NpcActionCompletion>): Boolean {
        if (tick < lastTick || sequence > Long.MAX_VALUE - 32) return false
        val before = previous
        if (task != null && (before == null || task.taskId != before.taskId)) {
            append(tick, OperationEventKind.TASK_ASSIGNED, task)
            terminal(task.state)?.let { append(tick, it, task) }
        } else if (task != null && before != null) {
            if (task.controlRevision != before.controlRevision) {
                append(tick, OperationEventKind.CONTROL_CHANGED, task,
                    coalesced = task.controlRevision - before.controlRevision != 1L)
            }
            if (task.definitionRevision != before.definitionRevision) {
                append(tick, OperationEventKind.DEFINITION_CHANGED, task,
                    coalesced = task.definitionRevision.toLong() - before.definitionRevision != 1L)
            }
            if (task.frames.map { it.frameId } != before.frames.map { it.frameId } ||
                task.completedInterruptions != before.completedInterruptions) {
                append(tick, OperationEventKind.TASK_STACK_CHANGED, task,
                    coalesced = task.completedInterruptions - before.completedInterruptions > 1)
            }
            if (task.totalFailures > before.totalFailures) {
                append(tick, OperationEventKind.RETRY_OBSERVED, task,
                    coalesced = task.totalFailures - before.totalFailures > 1)
            }
            if (task.state != before.state) {
                val terminal = terminal(task.state)
                if (terminal != null) append(tick, terminal, task)
                else if (task.controlRevision == before.controlRevision && task.totalFailures == before.totalFailures) {
                    append(tick, OperationEventKind.STATE_CHANGED, task)
                }
            }
        } else if (before != null) {
            append(tick, OperationEventKind.STATE_CHANGED, null)
        }
        val currentCompletions = completions.takeLast(16)
        val sampledKeys = seenCompletions.toMutableSet()
        for (completion in currentCompletions) {
            val result = completion.result
            val failed = result.status == NpcActionStatus.FAILED || result.status == NpcActionStatus.REJECTED ||
                result.status == NpcActionStatus.UNSUPPORTED
            if (sampledKeys.add(key(completion)) && failed &&
                result.code != NpcActionCode.CANCELLED && result.code != NpcActionCode.EXPIRED) {
                // Core completion has no Behavior task correlation; do not invent causality.
                append(completion.gameTime, OperationEventKind.ACTION_FAILED, null, completion)
            }
        }
        previous = task
        seenCompletions = currentCompletions.map(::key).toSet()
        lastTick = tick
        return true
    }

    fun read(afterSequence: Long = 0): OperationJournalState.Recorded {
        require(afterSequence >= 0)
        return OperationJournalState.Recorded(id, coverageStartTick, lastTick,
            entries.peekFirst()?.sequence ?: sequence + 1, sequence, discarded,
            entries.filter { it.sequence > afterSequence })
    }

    private fun append(tick: Long, kind: OperationEventKind, task: OperationTaskSnapshot?,
                       completion: NpcActionCompletion? = null, coalesced: Boolean = false) {
        val frame = task?.frames?.lastOrNull()
        val result = completion?.result
        val event = OperationJournalEvent(++sequence, tick, kind, task?.taskId, task?.objectiveId,
            frame?.frameId, frame?.operationId, task?.state, task?.reason?.take(64), task?.definitionRevision,
            task?.controlRevision, task?.totalFailures, frame?.failures, frame?.attemptLimit,
            result?.actionId, result?.code, result?.channel, coalesced)
        if (entries.size == capacity) { entries.removeFirst(); discarded++ }
        entries.addLast(event)
    }

    private fun key(completion: NpcActionCompletion) = CompletionKey(completion.result.actionId,
        completion.gameTime, completion.result.code, completion.result.status, completion.result.channel)

    private fun terminal(state: OperationTaskState): OperationEventKind? = when (state) {
        OperationTaskState.COMPLETED -> OperationEventKind.TASK_COMPLETED
        OperationTaskState.FAILED -> OperationEventKind.TASK_FAILED
        OperationTaskState.CANCELLED -> OperationEventKind.TASK_CANCELLED
        else -> null
    }
}
