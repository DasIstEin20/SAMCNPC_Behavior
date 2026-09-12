package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import kotlin.math.abs

/** Inclusive geometry, separate from navigation and container interaction reach. */
internal data class WorkBox(val min: NpcBlockPosition, val max: NpcBlockPosition) {
    fun contains(position: NpcBlockPosition): Boolean = position.x in min.x..max.x &&
        position.y in min.y..max.y && position.z in min.z..max.z

    fun validationProblem(): String? {
        for (position in listOf(min, max)) {
            if (abs(position.x.toLong()) > 29_999_984 || abs(position.z.toLong()) > 29_999_984 || abs(position.y.toLong()) > 2048) return "work coordinates exceed supported world bounds"
        }
        if (max.x.toLong() - min.x !in 0..49 || max.z.toLong() - min.z !in 0..49 || max.y.toLong() - min.y !in 0..63) {
            return "work box must contain 1..50 by 1..64 by 1..50 blocks with ordered corners"
        }
        return null
    }

    val width: Int get() = max.x - min.x + 1
    val depth: Int get() = max.z - min.z + 1
    val height: Int get() = max.y - min.y + 1
}

internal class WorkArea(val bounds: WorkBox, exclusions: List<WorkBox> = emptyList()) {
    val exclusions: List<WorkBox> = java.util.List.copyOf(exclusions)
    fun validationProblem(): String? {
        bounds.validationProblem()?.let { return it }
        if (exclusions.size > 16 || exclusions.distinct().size != exclusions.size) return "work exclusions must be unique and limited to 16"
        for (excluded in exclusions) {
            excluded.validationProblem()?.let { return it }
            if (!bounds.contains(excluded.min) || !bounds.contains(excluded.max)) return "an exclusion must lie inside the work box"
        }
        return null
    }
    fun contains(position: NpcBlockPosition): Boolean = bounds.contains(position) && exclusions.none { it.contains(position) }
    override fun equals(other: Any?): Boolean = other is WorkArea && bounds == other.bounds && exclusions == other.exclusions
    override fun hashCode(): Int = 31 * bounds.hashCode() + exclusions.hashCode()
    override fun toString(): String = "WorkArea(bounds=$bounds, exclusions=$exclusions)"
    val columns: Int get() = bounds.width * bounds.depth
    fun column(index: Int): NpcBlockPosition {
        require(index in 0 until columns)
        return NpcBlockPosition(bounds.min.x + index % bounds.width, bounds.min.y, bounds.min.z + index / bounds.width)
    }
}
