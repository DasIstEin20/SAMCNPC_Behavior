package io.samcnpc.behavior.api

/** Counts are retained task evidence; none assert that an old world observation is still current. */
enum class OperationCountUnit { ITEMS, BLOCKS, LAYOUTS, VOLUMES, USES, CATCHES, CASTS, TICKS, STEPS, DEFEATS, ROUNDS, INDEX, CELLS, ATTEMPTS }
@ConsistentCopyVisibility
data class OperationMeasuredCount internal constructor(val name: String, val value: Long, val unit: OperationCountUnit)

sealed interface OperationProgress {
    /** No state has been captured for this frame yet. This is not a zero-progress measurement. */
    data object NotInitialized : OperationProgress
    class Observed internal constructor(
        val phase: String?,
        counters: List<OperationMeasuredCount>,
        val uncertain: Boolean? = null,
        val reconciliationRequired: Boolean? = null,
        val stopReason: String? = null,
    ) : OperationProgress {
        val counters: List<OperationMeasuredCount> = java.util.List.copyOf(counters)
        init { require(counters.size <= 32 && counters.map { it.name }.distinct().size == counters.size) }
    }
}
