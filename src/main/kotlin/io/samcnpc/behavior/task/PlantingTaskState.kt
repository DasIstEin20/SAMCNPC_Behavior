package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import java.util.UUID

internal enum class PlantingPhase { SELECT, PLACE, SUPPLY, RETURN, DONE }
internal enum class PlantingProblem { UNOBSERVABLE, OCCUPIED, ALREADY_PLANTED, GROWN_TREE, INVALID_SOIL, OBSTRUCTED, MISSING_SAPLINGS, UNREACHABLE, CHANGED, SUPPLY_ENDED, NO_ELIGIBLE_SITES }
internal class PlantingPlot(val base: NpcBlockPosition,initial: Collection<NpcBlockPosition> = emptyList()) {
    val initial: Set<NpcBlockPosition> = java.util.Set.copyOf(initial)
    val placed=linkedSetOf<NpcBlockPosition>()
    fun complete(species: SaplingSpecies) = placed.isNotEmpty() && initial+placed == species.footprint(base).toSet()
}
internal class PlantingTaskState(val resources: HarvestResources) {
    var phase=PlantingPhase.SELECT
    var cursor=0
    var selected: NpcBlockPosition? = null
    var supplyFrame: UUID? = null
    var stop: PlantingProblem? = null
    var detail="no planting effect yet"
    val plots=linkedMapOf<NpcBlockPosition,PlantingPlot>()
    val skipped=linkedMapOf<NpcBlockPosition,PlantingProblem>()
    val withdrawals=linkedMapOf<NpcBlockPosition,Map<String,Int>>()
    val deliveries=linkedMapOf<NpcBlockPosition,Map<String,Int>>()
    val checkpoints=linkedMapOf<NpcBlockPosition,ContainerCheckpoint>()
    var reconcileWorld=false
    var reconcileCursor=0
    fun planted()=plots.values.sumOf { it.placed.size }
    fun completed(d: PlantingTaskDefinition)=plots.values.count { it.complete(d.work.species) }
    fun goal(d: PlantingTaskDefinition)=completed(d) >= d.quantity && selected == null && plots.values.all { it.complete(d.work.species) }
}
