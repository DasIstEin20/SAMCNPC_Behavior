package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import kotlin.math.abs

internal enum class SaplingSpecies(val blockId: String,val logId: String,val layoutSize: Int,val clearanceHeight: Int) {
    OAK("minecraft:oak_sapling","minecraft:oak_log",1,5),
    BIRCH("minecraft:birch_sapling","minecraft:birch_log",1,6),
    DARK_OAK("minecraft:dark_oak_sapling","minecraft:dark_oak_log",2,8);
    val leafId: String = blockId.replace("_sapling","_leaves")
    val clearanceMargin: Int get() = if (layoutSize == 2) 2 else 1
    fun footprint(base: NpcBlockPosition): List<NpcBlockPosition> = (0 until layoutSize).flatMap { z ->
        (0 until layoutSize).map { x -> NpcBlockPosition(base.x+x,base.y,base.z+z) }
    }
}
internal enum class PlantingMode { PATCH, GAPS }

/** Grid or explicit bases are resolved once; every member of a layout must be authorized. */
internal class PlantingWorkOrder(
    val area: WorkArea,
    val species: SaplingSpecies,
    val mode: PlantingMode,
    val spacing: Int = 6,
    positions: List<NpcBlockPosition>? = null,
    val sources: ContainerChoices? = null,
    val keepSaplings: Int = 0,
    val sourceKeep: Int = 0,
) {
    val positions: List<NpcBlockPosition>? = positions?.let { java.util.List.copyOf(it) }
    fun validationProblem(): String? {
        val problem=area.validationProblem() ?: sources?.validationProblem()
        if (problem != null) return problem
        if (area.bounds.height != 1 || spacing !in species.layoutSize+2..16 || keepSaplings !in 0..512 || sourceKeep !in 0..2304) return "invalid planting field, spacing or sapling reserve"
        val explicit=positions
        if (explicit != null) {
            if (explicit.size !in 1..128 || explicit.distinct().size != explicit.size) return "planting requires1..128 unique explicit bases"
            if (explicit.any { base -> species.footprint(base).any { !area.contains(it) } }) return "explicit planting layout escaped its authorized area"
            for (a in explicit.indices) for (b in 0 until a) if (maxOf(abs(explicit[a].x-explicit[b].x),abs(explicit[a].z-explicit[b].z)) < spacing) return "explicit planting layouts violate supplied spacing"
        }
        if (sources?.positions.orEmpty().any(area::contains)) return "sapling source cannot occupy the planting plane"
        return null
    }
    val sites: List<NpcBlockPosition> = if (validationProblem() != null) emptyList() else java.util.List.copyOf(
        positions ?: buildList {
            val box=area.bounds
            for (z in box.min.z..box.max.z step spacing) for (x in box.min.x..box.max.x step spacing) {
                val base=NpcBlockPosition(x,box.min.y,z)
                if (species.footprint(base).all(area::contains)) add(base)
            }
        }
    )
    fun withSources(value: ContainerChoices?) = PlantingWorkOrder(area,species,mode,spacing,positions,value,keepSaplings,sourceKeep)
    override fun equals(other: Any?): Boolean = other is PlantingWorkOrder && area == other.area && species == other.species && mode == other.mode && spacing == other.spacing && positions == other.positions && sources == other.sources && keepSaplings == other.keepSaplings && sourceKeep == other.sourceKeep
    override fun hashCode(): Int = listOf(area,species,mode,spacing,positions,sources,keepSaplings,sourceKeep).hashCode()
}

internal data class PlantingTaskDefinition(
    override val dimensionId: String,
    val work: PlantingWorkOrder,
    val quantity: Int,
    override val anchor: NpcPosition,
    override val travelRadius: Double = 64.0,
    override val returnTo: NpcPosition? = null,
    override val budget: TaskBudget = TaskBudget(ticks=6000),
    override val version: Int = 1,
) : LocalWorkDefinition {
    override val operationId = ID
    override fun validationProblem(): String? {
        val problem=work.validationProblem() ?: NavigateTaskDefinition(dimensionId,anchor,budget=budget).validationProblem()
        if (problem != null) return problem
        if (version != 1 || work.sites.size !in 1..128 || quantity !in 1..128 || !travelRadius.isFinite() || travelRadius !in 4.0..64.0) return "planting version/site count/quota/travel radius exceeds bounds"
        val points=work.sites.flatMap(work.species::footprint).map(TransportTaskDefinition::center)+work.sources?.positions.orEmpty().map(TransportTaskDefinition::center)+listOfNotNull(anchor,returnTo)
        if (points.any { !contains(it) }) return "planting endpoints escaped the fixed travel boundary"
        if (points.any { a -> points.any { b -> TaskNavigator.distanceSquared(a,b) > 58.0*58.0 } }) return "planting endpoints exceed bounded local observations"
        for (point in points) NavigateTaskDefinition(dimensionId,point,budget=budget).validationProblem()?.let { return it }
        return null
    }
    companion object { const val ID="samcnpc:plant_trees" }
}
