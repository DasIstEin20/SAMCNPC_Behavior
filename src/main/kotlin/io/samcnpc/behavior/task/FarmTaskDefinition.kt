package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

internal enum class FarmCrop(val blockId: String,val itemId: String,val seedId: String) {
    WHEAT("minecraft:wheat","minecraft:wheat","minecraft:wheat_seeds"),
    CARROT("minecraft:carrots","minecraft:carrot","minecraft:carrot"),
    POTATO("minecraft:potatoes","minecraft:potato","minecraft:potato");
    val collectedItems: Set<String> = setOf(itemId,seedId)
}
internal enum class FarmMode { HARVEST, REPLANT, CULTIVATE }
internal data class FarmWorkOrder(
    val area: WorkArea,
    val crop: FarmCrop,
    val mode: FarmMode,
    val cycles: Int = 1,
    val prepareSoil: Boolean = false,
    val seedSources: ContainerChoices? = null,
    val keepSeeds: Int = 0,
    val sourceKeepSeeds: Int = 0,
    val growthWaitTicks: Int = 2400,
    val growthCheckTicks: Int = 100,
) {
    fun validationProblem(): String? {
        val problem=area.validationProblem() ?: seedSources?.validationProblem()
        if (problem != null) return problem
        if (area.bounds.height != 1 || area.columns !in 1..512) return "farm requires one crop-height plane with1..512 cells"
        if (cycles !in 1..8 || keepSeeds !in 0..512 || sourceKeepSeeds !in 0..2304) return "farm cycles/reserves exceed bounds"
        if (growthWaitTicks !in 20..36000 || growthCheckTicks !in 20..200 || growthCheckTicks > growthWaitTicks) return "farm growth wait/check interval exceeds bounds"
        if (mode == FarmMode.HARVEST && (cycles != 1 || prepareSoil || seedSources != null || keepSeeds != 0)) return "harvest-only work cannot prepare, resow, reserve seed or repeat cycles"
        if (seedSources?.positions.orEmpty().any(area::contains)) return "seed sources cannot occupy the farm field"
        return null
    }
    val cells: List<NpcBlockPosition> = if (validationProblem() == null) java.util.List.copyOf((0 until area.columns).map(area::column).filter(area::contains)) else emptyList()
}

/** A finite crop-yield objective; maintaining a field never grants unbounded growth polling. */
internal data class FarmTaskDefinition(
    override val dimensionId: String,
    val work: FarmWorkOrder,
    override val destinations: ContainerChoices,
    val quantity: Int,
    override val anchor: NpcPosition,
    override val travelRadius: Double = 64.0,
    override val returnTo: NpcPosition? = null,
    override val budget: TaskBudget = TaskBudget(ticks=12000),
    override val version: Int = 1,
) : ProducedTaskDefinition {
    override val operationId = ID
    override val outputs = WorkResourceIds(listOf(work.crop.itemId))
    override fun validationProblem(): String? {
        if (version != 1 || quantity !in 1..2304 || !travelRadius.isFinite() || travelRadius !in 4.0..64.0) return "invalid farm version, quantity or travel boundary"
        val problem=work.validationProblem() ?: destinations.validationProblem() ?: NavigateTaskDefinition(dimensionId,anchor,budget=budget).validationProblem()
        if (problem != null) return problem
        if (work.cells.isEmpty()) return "farm exclusions leave no authorized field cells"
        if (destinations.positions.any(work.area::contains)) return "crop recipient cannot occupy the field"
        val points=work.cells.map(TransportTaskDefinition::center)+(destinations.positions+work.seedSources?.positions.orEmpty()).map(TransportTaskDefinition::center)+listOfNotNull(returnTo,anchor)
        if (points.any { !contains(it) }) return "farm/source/recipient/return exceeds the fixed travel boundary"
        // Corners bound this planar field; endpoint comparisons remain a load-time validation cost.
        if (points.any { a -> points.any { b -> TaskNavigator.distanceSquared(a,b) > 58.0*58.0 } }) return "farm endpoints exceed bounded local observation"
        for (point in points) NavigateTaskDefinition(dimensionId,point,budget=budget).validationProblem()?.let { return it }
        return null
    }
    companion object { const val ID="samcnpc:farm" }
}
