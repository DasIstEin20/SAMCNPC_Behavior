package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcActionResult
import java.util.UUID

data class OperationReply(
    val result: NpcActionResult,
    val observation: OperationObservation?,
    val amendment: OperationAmendmentSnapshot? = null,
)

/** A value copy at one server tick; no frame, definition, NBT, entity or action handle escapes. */
data class OperationObservation(
    val npcUuid: UUID,
    val dimensionId: String,
    val observedTick: Long,
    val task: OperationTaskSnapshot?,
)

enum class OperationTaskState { RUNNING, WAITING, PAUSED, COMPLETED, CANCELLED, FAILED }

class OperationTaskSnapshot internal constructor(
    val taskId: UUID,
    val objectiveId: UUID,
    val definitionRevision: Int,
    val controlRevision: Long,
    val state: OperationTaskState,
    val reason: String,
    val detail: String,
    val totalFailures: Int,
    val completedInterruptions: Int,
    val pendingAmendmentId: UUID?,
    frames: List<OperationFrameSnapshot>,
) {
    /** Primary first, current active interruption last; at most three entries. */
    val frames: List<OperationFrameSnapshot> = java.util.List.copyOf(frames)
}

data class OperationFrameSnapshot(
    val frameId: UUID,
    val operationId: String,
    val definitionVersion: Int,
    val remainingTicks: Int,
    val durationTicks: Int,
    val failures: Int,
    val attemptLimit: Int,
    val waitTicks: Int,
    val reason: String,
)
