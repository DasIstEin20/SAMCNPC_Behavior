package io.samcnpc.behavior.task

/** Read-only, bounded pages expose preserved facts without rebuilding live execution state. */
internal object TaskHistory {
    fun objectives(record: TaskRecord, index: Int? = null, page: Int = 1): String {
        val reports = record.amendments.objectives
        if (index == null) return "task=${record.id}; currentObjective=${record.amendments.objectiveId}; archived=${reports.size}/8" +
            reports.mapIndexed { i, report -> "\n${i + 1}: objective=${report.objectiveId}; operation=${report.definition.operationId}; revision=${report.revision}; confirmed=${report.confirmed}" }.joinToString("")
        require(index in 1..reports.size) { "objective index must be within the reported archive" }
        val report = reports[index - 1]
        val lines = mutableListOf("dimension=${report.definition.dimensionId}; ${report.detail}")
        for ((id, counts) in report.resources.toSortedMap()) lines.add("item=$id; initial=${counts.initial}; gathered=${counts.gathered}; supplied=${counts.supplied}; consumed=${counts.consumed}; delivered=${counts.delivered}; lost=${counts.lost}; retained=${counts.retained}")
        for ((position, items) in report.deliveries.toSortedMap(compareBy<io.samcnpc.core.api.NpcBlockPosition> { it.x }.thenBy { it.y }.thenBy { it.z })) {
            for ((id, count) in items.toSortedMap()) lines.add("recipient=$position; item=$id; confirmed=$count")
        }
        return page("objective=${report.objectiveId}; operation=${report.definition.operationId}; revision=${report.revision}; confirmed=${report.confirmed}", lines, page)
    }
    fun amendments(record: TaskRecord, page: Int = 1): String = page("task=${record.id}; revision=${record.amendments.revision}; pending=${record.amendments.pending?.requestId}; expires=${record.amendments.pending?.expiresTick}",
        record.amendments.receipts.map { "request=${it.request.requestId}; outcome=${it.outcome}; revision=${it.revision}; ${it.detail}" }, page)
    fun inventory(record: TaskRecord, index: Int? = null, page: Int = 1): String {
        val outcomes = record.logistics.outcomes
        if (index == null) return page("task=${record.id}; inventoryOutcomes=${outcomes.size}/32", outcomes.mapIndexed { i, o ->
            "${i+1}: frame=${o.frameId}; kind=${o.kind}; originalRevision=${o.revision}; reason=${o.reason}; returned=${o.returned}"
        },page)
        require(index in 1..outcomes.size) { "inventory outcome index is outside the recorded history" }
        val report = outcomes[index-1]; val lines = mutableListOf(report.detail)
        for ((id,count) in report.goals.toSortedMap()) lines.add("captured=$count; item=$id")
        for ((id,count) in report.picked.toSortedMap()) lines.add("deliberatePickup=$count; item=$id")
        for ((kind,rows) in listOf("source" to report.sources,"recipient" to report.recipients)) {
            for ((position,items) in rows.toSortedMap(compareBy<io.samcnpc.core.api.NpcBlockPosition> { it.x }.thenBy { it.y }.thenBy { it.z })) {
                for ((id,count) in items.toSortedMap()) lines.add("$kind=$position; item=$id; transferred=$count")
            }
        }
        return page("frame=${report.frameId}; kind=${report.kind}; originalRevision=${report.revision}; reason=${report.reason}; returned=${report.returned}",lines,page)
    }
    private fun page(header: String, lines: List<String>, page: Int): String {
        val count = maxOf(1, (lines.size + 7) / 8)
        require(page in 1..count) { "report page must be 1..$count" }
        return "$header; page=$page/$count" + lines.drop((page - 1) * 8).take(8).joinToString(separator = "\n", prefix = if (lines.isEmpty()) "" else "\n")
    }
}
