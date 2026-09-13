package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.OperationControlRequest
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult

/** Pure gate shared by the public control adapter's server-thread entry point. */
internal object TaskControlGuard {
    fun rejection(record: TaskRecord, request: OperationControlRequest, now: Long): NpcActionResult? {
        if (request.expectedControlRevision < 0 || request.expectedDefinitionRevision !in 0..TaskAmendmentState.MAX_REQUESTS ||
            request.issuedTick < 0 || request.expiresTick <= request.issuedTick ||
            request.expiresTick - request.issuedTick > 1200) {
            return NpcActionResult.rejected("control revisions and issue/expiry must be bounded; lifetime is 1..1200 ticks")
        }
        if (record.id != request.taskId || record.controlRevision != request.expectedControlRevision ||
            record.amendments.revision != request.expectedDefinitionRevision) {
            return NpcActionResult.rejected("task or revision changed; reobserve before deciding another control", NpcActionCode.CONFLICT)
        }
        if (now < request.issuedTick || now >= request.expiresTick) {
            return NpcActionResult.rejected("control expired or was issued in the future", NpcActionCode.NOT_READY)
        }
        if (record.status.terminal) return NpcActionResult.rejected("task already has a final report", NpcActionCode.NOT_READY)
        return null
    }
}
