package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.core.api.*

/** Bounded supported stance selection; callers provide their own access/area restrictions. */
internal object ContainerApproachKernel {
    fun admitSelection(world: NpcWorldView, offset: Int = 1): Boolean {
        require(offset in 1..2)
        val span = 2 * offset + 1
        // Standing-space fact plus the wood caller's two bounded foliage predicates per cell.
        return io.samcnpc.behavior.runtime.BehaviorPlanning.admit(world, span * span * span * 3, io.samcnpc.behavior.kernel.work.PlanningKind.CONTAINER)
    }
    fun select(world: NpcWorldView, container: NpcBlockPosition, origin: NpcPosition,
               offset: Int = 1, maximumDistance: Double? = null, preferredDistance: Double? = null,
               skipColumn: Boolean = true, allowed: (NpcBlockPosition) -> Boolean = { true }): NpcPosition? {
        require(offset in 1..2)
        val center = NpcPosition(container.x + 0.5, container.y + 0.5, container.z + 0.5)
        val candidates = ArrayList<NpcPosition>(125)
        for (x in -offset..offset) for (z in -offset..offset) for (y in -offset..offset) {
            if (skipColumn && x == 0 && z == 0) continue
            val cell = NpcBlockPosition(container.x + x, container.y + y, container.z + z)
            val position = NpcPosition(cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5)
            if (maximumDistance != null && distanceSquared(position, center) > maximumDistance * maximumDistance) continue
            if (!allowed(cell)) continue
            val space = world.observeStandingSpace(position) ?: continue
            if (space.clear && space.supported && !space.inFluid) candidates.add(position)
        }
        val preferred = if (preferredDistance == null) emptyList() else candidates.filter { distanceSquared(it, center) <= preferredDistance * preferredDistance }
        val choices = if (preferred.isEmpty()) candidates else preferred
        return choices.minWithOrNull(compareBy<NpcPosition> { distanceSquared(origin, it) }.thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
    }
    private fun distanceSquared(a: NpcPosition, b: NpcPosition): Double {
        val x = a.x - b.x; val y = a.y - b.y; val z = a.z - b.z
        return x * x + y * y + z * z
    }
}
