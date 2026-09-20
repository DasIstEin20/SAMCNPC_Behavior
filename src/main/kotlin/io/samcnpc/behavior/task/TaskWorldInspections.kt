package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcSnapshot

/** Invoked only inside TaskSupervision.withNpc; ordinary task inspection does no world scan. */
internal object TaskWorldInspections {
    fun capture(npc: NpcFacade, physical: NpcSnapshot, request: OperationWorldRequest): OperationWorldInspection {
        val view = npc.worldView()
        val entities = request.entities?.let(view::observeVisibleEntities)
        val blocks = request.blocks.map { position -> OperationBlockInspection(position, view.observeVisibleBlock(position)) }
        return OperationWorldInspection(physical.dimensionId, physical.gameTime, entities, blocks)
    }
}
