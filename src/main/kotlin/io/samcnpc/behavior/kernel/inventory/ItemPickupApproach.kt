package io.samcnpc.behavior.kernel.inventory

import io.samcnpc.core.api.NpcEntityObservation
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcWorldView
import kotlin.math.floor

/** A reachable pickup or a supplied standing cell, never the height of a floating item. */
internal object ItemPickupApproach {
    data class Selection(val drop: NpcEntityObservation, val standing: NpcPosition?)
    private val OFFSETS = listOf(0 to 0, -1 to 0, 0 to -1, 0 to 1, 1 to 0)

    fun admitSelection(origin: NpcPosition, world: NpcWorldView, drops: List<NpcEntityObservation>): Boolean =
        drops.isEmpty() || drops.any { distanceSquared(origin, it.position) <= 4.0 } ||
            io.samcnpc.behavior.runtime.BehaviorPlanning.admit(world, minOf(64, drops.size) * 20, io.samcnpc.behavior.kernel.work.PlanningKind.PICKUP)

    fun select(origin: NpcPosition, world: NpcWorldView, drops: List<NpcEntityObservation>): Selection? {
        val ordered = drops.sortedWith(compareBy<NpcEntityObservation> { distanceSquared(origin, it.position) }.thenBy { it.uuid.toString() })
        for (drop in ordered.take(64)) {
            // Core permits a real nearby pickup even if that item is airborne or on a higher
            // ledge. The collector must not invent an additional relative-height prohibition.
            if (distanceSquared(origin, drop.position) <= 4.0) return Selection(drop, null)
            val columnX = floor(drop.position.x).toInt()
            val columnY = floor(drop.position.y).toInt()
            val columnZ = floor(drop.position.z).toInt()
            var best: NpcPosition? = null
            var bestDistance = Double.POSITIVE_INFINITY
            // A settled item supplies a candidate height for farmland, slabs and other
            // partial surfaces. It is still accepted only after Core confirms actual foot
            // contact and body clearance; an airborne item's height grants no support.
            val heights = doubleArrayOf(columnY - 1.0, columnY.toDouble(), columnY + 1.0, drop.position.y)
            for ((x, z) in OFFSETS) for (y in heights) {
                val feet = NpcPosition(columnX + x + 0.5, y, columnZ + z + 0.5)
                // Leave margin for the navigation endpoint rather than stopping just beyond
                // Core's exact two-block pickup radius.
                if (distanceSquared(feet, drop.position) > 1.25 * 1.25) continue
                val standing = world.observeStandingSpace(feet) ?: continue
                if (!standing.clear || !standing.supported || standing.inFluid) continue
                val distance = distanceSquared(origin, feet)
                if (distance < bestDistance) { best = feet; bestDistance = distance }
            }
            if (best != null) return Selection(drop, best)
        }
        return null
    }

    private fun distanceSquared(a: NpcPosition, b: NpcPosition): Double {
        val x = a.x - b.x; val y = a.y - b.y; val z = a.z - b.z
        return x * x + y * y + z * z
    }
}
