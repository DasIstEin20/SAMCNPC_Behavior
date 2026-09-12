package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTactics
import java.util.UUID

internal enum class QuantityChangeMode { TOTAL, ADD }
internal enum class ObjectiveChangeMode { PRESERVE, NEW_OBJECTIVE }
internal sealed interface TaskChange {
    data class Quantity(val amount: Int, val mode: QuantityChangeMode) : TaskChange
    data class Redirect(val recipients: ContainerChoices) : TaskChange
    data class Sources(val sources: ContainerChoices?) : TaskChange
    data class Replace(val definition: TaskDefinition, val objective: ObjectiveChangeMode = ObjectiveChangeMode.PRESERVE) : TaskChange
    data class ExtendTime(val ticks: Int) : TaskChange
    data class Tactics(val tactics: CombatTactics) : TaskChange
    data class Reaction(val policy: TaskReactionPolicy) : TaskChange
    data class Logistics(val policy: TaskLogisticsPolicy) : TaskChange
}

internal data class TaskAmendmentRequest(
    val taskId: UUID, val requestId: UUID, val actorUuid: UUID, val expectedRevision: Int,
    val issuedTick: Long, val expiresTick: Long, val change: TaskChange,
) {
    fun same(other: TaskAmendmentRequest): Boolean = taskId == other.taskId && requestId == other.requestId && actorUuid == other.actorUuid &&
        expectedRevision == other.expectedRevision && issuedTick == other.issuedTick && expiresTick == other.expiresTick &&
        TaskChangeCodec.write(change) == TaskChangeCodec.write(other.change)
    fun validationProblem(): String? = when {
        expectedRevision !in 0..TaskAmendmentState.MAX_REQUESTS -> "expectedRevision exceeds the amendment limit"
        issuedTick < 0 || expiresTick <= issuedTick || expiresTick - issuedTick > 1200 -> "request expiry must be within 1..1200 ticks of issue"
        else -> TaskChanges.validationProblem(change)
    }
}
internal enum class TaskAmendmentOutcome { APPLIED, REJECTED, EXPIRED }
internal data class TaskAmendmentReceipt(val request: TaskAmendmentRequest, val revision: Int, val outcome: TaskAmendmentOutcome, val detail: String)

internal data class TaskObjectiveReport(
    val objectiveId: UUID, val revision: Int, val definition: TaskDefinition, val confirmed: Int,
    val resources: Map<String, HarvestResource>, val deliveries: Map<io.samcnpc.core.api.NpcBlockPosition, Map<String, Int>>,
    val detail: String,
    val mining: MiningObjectiveEvidence? = null,
    val food: FoodObjectiveEvidence? = null,
    val farming: FarmObjectiveEvidence? = null,
    val planting: PlantingObjectiveEvidence? = null,
)

/** Receipts are retained for this task's entire lifetime; expiry never frees a replay ID. */
internal class TaskAmendmentState(
    var objectiveId: UUID, var revision: Int = 0,
    val receipts: MutableList<TaskAmendmentReceipt> = mutableListOf(),
    val objectives: MutableList<TaskObjectiveReport> = mutableListOf(),
    var pending: TaskAmendmentRequest? = null,
) {
    fun replay(request: TaskAmendmentRequest): String? {
        val previous = receipts.firstOrNull { it.request.requestId == request.requestId }
        if (previous != null) return if (previous.request.same(request)) "${previous.outcome}: revision=${previous.revision}; ${previous.detail}"
            else "CONFLICT: request ID was already used for different parameters or an actor"
        val queued = pending
        if (queued?.requestId == request.requestId) return if (queued.same(request)) "PENDING: waiting for a safe boundary; revision=$revision"
            else "CONFLICT: pending request ID has different parameters or an actor"
        return null
    }
    companion object { const val MAX_REQUESTS = 32; const val MAX_OBJECTIVES = 8 }
}
