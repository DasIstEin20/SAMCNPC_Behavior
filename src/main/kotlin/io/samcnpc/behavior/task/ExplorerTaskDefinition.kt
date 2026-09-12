package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcNavigationBounds
import io.samcnpc.core.api.NpcPosition
import kotlin.math.floor

internal data class ExplorerTaskDefinition(
    override val dimensionId: String,
    val anchor: NpcPosition,
    val radius: Int = 32,
    val cellStep: Int = 4,
    val maxCells: Int = 32,
    val verticalRange: Int = 12,
    val chunkBudget: Int = 64,
    val heading: Int = 0,
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
) : TaskDefinition {
    override val operationId = ID
    val bounds: NpcNavigationBounds = NpcNavigationBounds(
        NpcPosition(anchor.x-radius,anchor.y-verticalRange,anchor.z-radius),
        NpcPosition(anchor.x+radius,anchor.y+verticalRange,anchor.z+radius),
    )
    // Native movement has finite width and inertia. Keep route nodes one block inside
    // the expedition envelope; bounds remain the authoritative displacement check.
    val routeBounds: NpcNavigationBounds = NpcNavigationBounds(
        NpcPosition(bounds.min.x+1.0,bounds.min.y,bounds.min.z+1.0),
        NpcPosition(bounds.max.x-1.0,bounds.max.y,bounds.max.z-1.0),
    )
    fun position(node: ExplorerNode) = NpcPosition(anchor.x+node.x*cellStep,node.y,anchor.z+node.z*cellStep)
    fun chunkFootprint(): Int {
        val width=floor((anchor.x+radius)/16).toInt()-floor((anchor.x-radius)/16).toInt()+3
        val depth=floor((anchor.z+radius)/16).toInt()-floor((anchor.z-radius)/16).toInt()+3
        return width*depth
    }
    override fun validationProblem(): String? = when {
        version != 1 -> "unsupported explorer definition version"
        NavigateTaskDefinition(dimensionId,anchor,budget=budget).validationProblem() != null -> "invalid exploration anchor, dimension or budget"
        radius !in 8..96 || cellStep !in 4..8 || verticalRange !in 1..16 -> "exploration radius/step/vertical range must be 8..96/4..8/1..16"
        maxCells !in 1..64 || heading !in 0..3 -> "exploration cells must be 1..64 and heading 0..3"
        chunkBudget !in 9..256 || chunkFootprint() > chunkBudget -> "chunk budget cannot cover the fixed envelope plus Core's activity margin"
        kotlin.math.abs(anchor.x)+radius > 29_999_984 || kotlin.math.abs(anchor.z)+radius > 29_999_984 -> "exploration bounds exceed supported world coordinates"
        else -> null
    }
    companion object { const val ID = "samcnpc:explore" }
}
