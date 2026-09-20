package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcVisualBlockRead
import io.samcnpc.core.api.NpcVisualEntityQuery
import io.samcnpc.core.api.NpcVisualEntityScan

/** Explicit opt-in; callers supply at most sixteen known/desired cells, never a hidden resource scan. */
class OperationWorldRequest(
    val entities: NpcVisualEntityQuery? = NpcVisualEntityQuery(),
    blocks: List<NpcBlockPosition> = emptyList(),
) {
    val blocks: List<NpcBlockPosition> = java.util.List.copyOf(blocks)
    init { require(blocks.size <= 16 && blocks.distinct().size == blocks.size) }
}

enum class OperationObservationSource { NPC_VISUAL_SENSOR }

/** All values belong to one authorized server-thread capture. No remembered cell is promoted to fact. */
class OperationWorldInspection internal constructor(
    val dimensionId: String,
    val observedTick: Long,
    /** Null means this sensor was not requested, not that there are no entities. */
    val entities: NpcVisualEntityScan?,
    blocks: List<OperationBlockInspection>,
) {
    val source: OperationObservationSource get() = OperationObservationSource.NPC_VISUAL_SENSOR
    val blocks: List<OperationBlockInspection> = java.util.List.copyOf(blocks)
}

data class OperationBlockInspection(val requested: NpcBlockPosition, val observation: NpcVisualBlockRead)
