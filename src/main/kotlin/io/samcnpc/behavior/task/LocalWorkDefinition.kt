package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcPosition

/** Fixed supplied travel envelope shared by physical local work, without a cargo requirement. */
internal sealed interface LocalWorkDefinition : TaskDefinition {
    val anchor: NpcPosition
    val travelRadius: Double
    val returnTo: NpcPosition?
    fun contains(position: NpcPosition): Boolean = TaskNavigator.distanceSquared(anchor,position) <= travelRadius*travelRadius
}
