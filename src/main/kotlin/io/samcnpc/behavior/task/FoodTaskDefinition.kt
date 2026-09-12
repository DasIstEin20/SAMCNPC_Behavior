package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import kotlin.math.floor

internal sealed interface FoodWorkOrder {
    val area: WorkArea?
    data class Drops(override val area: WorkArea) : FoodWorkOrder
    data class Berries(override val area: WorkArea) : FoodWorkOrder
    data class Stored(val sources: ContainerChoices, val sourceKeep: Int = 0) : FoodWorkOrder {
        override val area: WorkArea? = null
    }
    data class Hunt(override val area: WorkArea, val targets: NpcEntityTypeFilter, val limit: Int = 8) : FoodWorkOrder
}

/** Food delivery and the NPC's retained ration are independent quantities. Hunting is opt-in. */
internal data class FoodTaskDefinition(
    override val dimensionId: String,
    val work: FoodWorkOrder,
    override val outputs: WorkResourceIds,
    override val destinations: ContainerChoices,
    val quantity: Int,
    val keepFood: Int,
    override val anchor: NpcPosition,
    override val travelRadius: Double = 64.0,
    override val returnTo: NpcPosition? = null,
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
) : ProducedTaskDefinition {
    override val operationId = ID
    val volume: Int = work.area?.bounds?.let { it.width * it.height * it.depth } ?: 0
    override fun validationProblem(): String? {
        if (version != 1 || quantity !in 1..2304 || keepFood !in 0..64) return "food requires version1, delivery1..2304 and retained food0..64"
        if (!travelRadius.isFinite() || travelRadius !in 4.0..64.0) return "food travel radius must be4..64"
        val problem = NavigateTaskDefinition(dimensionId, anchor, budget=budget).validationProblem() ?: destinations.validationProblem() ?: work.area?.validationProblem()
        if (problem != null) return problem
        if (volume > 32768) return "food scan exceeds32768 cells"
        if (work is FoodWorkOrder.Berries && !outputs.matches(BERRY_ITEM)) return "wild berry gathering requires sweet berries in its food filter"
        if (work is FoodWorkOrder.Hunt && (work.targets.isEmpty || work.limit !in 1..16 || "minecraft:player" in work.targets.typeIds)) return "hunting requires explicit nonplayer types/tags and1..16 attempts"
        val sources = (work as? FoodWorkOrder.Stored)?.sources
        if (work is FoodWorkOrder.Stored) {
            if (work.sourceKeep !in 0..2304) return "food source reserve must be0..2304 per filtered item"
            sources?.validationProblem()?.let { return it }
            if (work.sources.positions.any { it in destinations.positions }) return "food source and recipient must differ"
        }
        val points = mutableListOf(anchor)
        points.addAll((sources?.positions.orEmpty()+destinations.positions).map(TransportTaskDefinition::center))
        returnTo?.let(points::add)
        work.area?.bounds?.let { box ->
            for (x in listOf(box.min.x,box.max.x)) for (y in listOf(box.min.y,box.max.y)) for (z in listOf(box.min.z,box.max.z)) points.add(NpcPosition(x+0.5,y+0.5,z+0.5))
        }
        for (point in points) {
            NavigateTaskDefinition(dimensionId,point,budget=budget).validationProblem()?.let { return it }
            if (!contains(point)) return "food endpoints exceed the fixed travel boundary"
        }
        if (points.any { a -> points.any { b -> TaskNavigator.distanceSquared(a,b) > 58.0*58.0 } }) return "food endpoints exceed bounded local observation"
        return null
    }
    fun inWork(position: NpcPosition): Boolean = work.area?.contains(cell(position)) == true
    fun scanCell(index: Int): NpcBlockPosition {
        require(index in 0 until volume)
        val box = checkNotNull(work.area).bounds
        return NpcBlockPosition(box.min.x+index%box.width,box.min.y+index/(box.width*box.depth),box.min.z+index/box.width%box.depth)
    }
    companion object {
        const val ID = "samcnpc:food"
        const val BERRY_ITEM = "minecraft:sweet_berries"
        fun cell(p: NpcPosition) = NpcBlockPosition(floor(p.x).toInt(),floor(p.y).toInt(),floor(p.z).toInt())
    }
}
