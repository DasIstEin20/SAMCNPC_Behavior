package io.samcnpc.behavior.task

import net.minecraft.nbt.*
import java.util.UUID

internal object TaskAmendmentCodec {
    fun write(state: TaskAmendmentState) = CompoundTag().apply {
        putUUID("objective", state.objectiveId); putInt("revision", state.revision)
        put("receipts", ListTag().apply { for (receipt in state.receipts) add(CompoundTag().apply {
            put("request", request(receipt.request)); putInt("revision", receipt.revision); putString("outcome", receipt.outcome.name); putString("detail", receipt.detail)
        }) })
        put("objectives", ListTag().apply { state.objectives.forEach { add(TaskObjectiveCodec.write(it)) } })
        state.pending?.let { put("pending", request(it)) }
    }
    fun read(tag: CompoundTag, taskId: UUID): TaskAmendmentState {
        require(tag.allKeys == setOf("objective", "revision", "receipts", "objectives") + (if (tag.contains("pending")) setOf("pending") else emptySet()) && tag.hasUUID("objective")) { "invalid amendment history" }
        val state = TaskAmendmentState(tag.getUUID("objective"), TaskChangeCodec.integer(tag, "revision"))
        var applied = 0
        val seen = hashSetOf<UUID>()
        for (raw in TaskObjectiveCodec.list(tag, "receipts", TaskAmendmentState.MAX_REQUESTS)) {
            val row = raw as CompoundTag
            require(row.allKeys == setOf("request", "revision", "outcome", "detail")) { "invalid amendment receipt" }
            val request = readRequest(TaskChangeCodec.compound(row, "request"), taskId)
            val outcome = TaskAmendmentOutcome.valueOf(row.getString("outcome"))
            require(request.expectedRevision == applied) { "amendment receipt revision history differs" }
            if (outcome == TaskAmendmentOutcome.APPLIED) applied++
            val revision = TaskChangeCodec.integer(row, "revision")
            require(revision == applied && row.getString("detail").length <= 256 && seen.add(request.requestId)) { "invalid/duplicate amendment receipt" }
            state.receipts.add(TaskAmendmentReceipt(request, revision, outcome, row.getString("detail")))
        }
        require(state.revision == applied) { "current revision differs from accepted receipts" }
        for (raw in TaskObjectiveCodec.list(tag, "objectives", TaskAmendmentState.MAX_OBJECTIVES)) {
            val report = TaskObjectiveCodec.read(raw as CompoundTag)
            require(report.revision < state.revision && report.objectiveId != state.objectiveId && state.objectives.none { it.objectiveId == report.objectiveId }) { "invalid objective lineage" }
            state.objectives.add(report)
        }
        if (tag.contains("pending")) {
            val pending = readRequest(TaskChangeCodec.compound(tag, "pending"), taskId)
            require(state.receipts.size < TaskAmendmentState.MAX_REQUESTS && pending.expectedRevision == state.revision && pending.requestId !in seen) { "invalid pending amendment" }
            state.pending = pending
        }
        return state
    }
    private fun request(value: TaskAmendmentRequest) = CompoundTag().apply {
        putUUID("task", value.taskId); putUUID("id", value.requestId); putUUID("actor", value.actorUuid)
        putInt("revision", value.expectedRevision); putLong("issued", value.issuedTick); putLong("expires", value.expiresTick); put("change", TaskChangeCodec.write(value.change))
    }
    private fun readRequest(tag: CompoundTag, taskId: UUID): TaskAmendmentRequest {
        require(tag.allKeys == setOf("task", "id", "actor", "revision", "issued", "expires", "change") &&
            tag.hasUUID("task") && tag.hasUUID("id") && tag.hasUUID("actor") && tag.getUUID("task") == taskId &&
            tag.contains("issued", Tag.TAG_LONG.toInt()) && tag.contains("expires", Tag.TAG_LONG.toInt())) { "invalid amendment identity/expiry" }
        val value = TaskAmendmentRequest(taskId, tag.getUUID("id"), tag.getUUID("actor"), TaskChangeCodec.integer(tag, "revision"),
            tag.getLong("issued"), tag.getLong("expires"), TaskChangeCodec.read(TaskChangeCodec.compound(tag, "change")))
        require(value.validationProblem() == null) { "invalid amendment request" }; return value
    }
}
