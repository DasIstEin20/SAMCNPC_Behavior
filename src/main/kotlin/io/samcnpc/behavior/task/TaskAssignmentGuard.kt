package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.OperationAssignmentRequest
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult

internal object TaskAssignmentGuard {
    fun rejection(prior: TaskRecord?, request: OperationAssignmentRequest, now: Long): NpcActionResult? {
        if (request.issuedTick < 0 || request.expiresTick <= request.issuedTick ||
            request.expiresTick - request.issuedTick > 1200) {
            return NpcActionResult.rejected("assignment issue/expiry must be bounded; lifetime is 1..1200 ticks")
        }
        if (prior?.id != request.expectedPriorTaskId) {
            return NpcActionResult.rejected("prior task changed; reobserve before deciding another assignment", NpcActionCode.CONFLICT)
        }
        if (now < request.issuedTick || now >= request.expiresTick) {
            return NpcActionResult.rejected("assignment expired or was issued in the future", NpcActionCode.NOT_READY)
        }
        if (prior != null && !prior.status.terminal) {
            return NpcActionResult.rejected("NPC already has an active or paused task; cancel it explicitly before replacement", NpcActionCode.CONFLICT)
        }
        return null
    }
}
