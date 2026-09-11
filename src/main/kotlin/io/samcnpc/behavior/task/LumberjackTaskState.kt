package io.samcnpc.behavior.task

import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.core.api.NpcBlockPosition

internal data class WorkBlockCheckpoint(val position: NpcBlockPosition, val blockId: String)

/** Durable work and resources live in their one parent task; all control remains transient. */
internal class LumberjackTaskState(
    val job: LumberjackDemoJob,
    val resources: HarvestResources,
    var containerCounts: Map<String, Int>,
    val containerSize: Int,
    var pendingBreak: WorkBlockCheckpoint? = null,
    removed: Collection<NpcBlockPosition> = emptyList(),
    var residuePreserved: Boolean = false,
) {
    val observedRemovedBlocks: MutableSet<NpcBlockPosition> = removed.toMutableSet()
    var reconcileWorld: Boolean = true
    var reconciliationCursor: Int = 0
    var reconciliationBlocks: List<NpcBlockPosition>? = null
    companion object { const val MAX_REMOVED_BLOCKS = 4096 }
}
