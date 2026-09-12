package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.core.api.*

/** Distant supported staging is movement permission only; actual interaction still needs visibility. */
internal object WorkApproachVisibility {
    fun allowsCandidate(world: NpcWorldView, origin: NpcPosition, feet: NpcPosition, target: NpcBlockPosition): Boolean {
        val x=origin.x-target.x-0.5; val y=origin.y-target.y-0.5; val z=origin.z-target.z-0.5
        // Core bounds hypothetical visibility origins to 12 blocks from the actual body.
        // Approach first rather than converting an out-of-range unknown into "unreachable".
        if (x*x+y*y+z*z > 8.0*8.0) return true
        return world.visibleBlockFrom(feet,target) == true
    }
}
