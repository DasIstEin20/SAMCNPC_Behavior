package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcPosition

/** Work permission, measured objective and transport boundary are independent and immutable. */
internal data class MiningTaskDefinition(
    override val dimensionId: String,
    val work: MiningWorkOrder,
    override val outputs: WorkResourceIds,
    override val destinations: ContainerChoices,
    val quantity: Int,
    val counting: MiningCounting,
    override val anchor: NpcPosition,
    override val travelRadius: Double = 64.0,
    override val returnTo: NpcPosition? = null,
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
) : ProducedTaskDefinition {
    val clearanceCells: Int = if ((work.method == MiningMethod.TUNNEL || work.method == MiningMethod.EXCAVATION) && work.validationProblem() == null)
        (0 until work.volume).count { work.area.contains(work.cell(it)) } else 0
    override val operationId: String = ID
    override fun validationProblem(): String? {
        if (version != 1) return "unsupported mining definition version"
        if (!travelRadius.isFinite() || travelRadius !in 4.0..64.0) return "mining travel radius must be 4..64 blocks"
        if (quantity !in 1..2304) return "mining quantity must be 1..2304"
        val problem=work.validationProblem() ?: destinations.validationProblem() ?: NavigateTaskDefinition(dimensionId,anchor,budget=budget).validationProblem()
        if (problem != null) return problem
        if (counting == MiningCounting.CLEARED_VOLUME && work.method != MiningMethod.TUNNEL && work.method != MiningMethod.EXCAVATION) return "complete clearance requires tunnel or excavation geometry"
        if (counting == MiningCounting.CLEARED_VOLUME && quantity != 1) return "clearance counts one complete supplied geometry"
        val box=work.area.bounds
        val corners=mutableListOf<NpcPosition>()
        for (x in listOf(box.min.x,box.max.x)) for (y in listOf(box.min.y,box.max.y)) for (z in listOf(box.min.z,box.max.z))
            corners.add(NpcPosition(x+0.5,y+0.5,z+0.5))
        val points=corners+destinations.positions.map(TransportTaskDefinition::center)+listOfNotNull(returnTo)
        for (point in points) {
            NavigateTaskDefinition(dimensionId,point,budget=budget).validationProblem()?.let { return it }
            if (!contains(point)) return "work, recipients and return must lie inside the fixed travel boundary"
        }
        if (points.any { a -> points.any { b -> TaskNavigator.distanceSquared(a,b) > 58.0*58.0 } }) return "mining endpoints must remain within bounded local observation"
        if (destinations.positions.any(work.area::contains)) return "recipient cannot be inside the removal area"
        return null
    }
    companion object { const val ID="samcnpc:mine" }
}

internal enum class MiningPhase { SELECT, WORK, COLLECT, DEPOSIT, RETURN }
internal class MiningTaskState(resources: ProducedResources) : ProducedCargoState(resources) {
    val selection=MiningSelectionState()
    var phase=MiningPhase.SELECT
    var target: MiningSelectionResult.Target? = null
    var collectionTicks=0
    var exhausted=false
    var stop: MiningProblem? = null
    var stopDetail: String = ""
    // A load rechecks journaled effects in slices before any new mutation.
    var reconcileCursor=0
    var reconcileWorld=false
    companion object { const val COLLECTION_TICKS=80; const val INITIAL_DROP_WAIT=20 }
    fun removedResources(definition: MiningTaskDefinition) = selection.removed.values.count(definition.work.resources::matches)
    fun confirmed(definition: MiningTaskDefinition): Int = when (definition.counting) {
        MiningCounting.DELIVERED_ITEMS -> delivered(definition)
        MiningCounting.REMOVED_RESOURCE_BLOCKS -> removedResources(definition)
        MiningCounting.CLEARED_VOLUME -> if (goal(definition)) 1 else 0
    }
    fun goal(definition: MiningTaskDefinition): Boolean = when (definition.counting) {
        MiningCounting.DELIVERED_ITEMS -> delivered(definition) >= definition.quantity
        MiningCounting.REMOVED_RESOURCE_BLOCKS -> removedResources(definition) >= definition.quantity
        MiningCounting.CLEARED_VOLUME -> exhausted && stop == null && selection.cleared.size == definition.clearanceCells
    }
}
