package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.core.api.NpcPosition
import java.util.UUID

/**
 * Per-NPC, transient route progress measurement. The caller chooses the route identity, goal and
 * recovery policy; this kernel only answers whether horizontal progress has stopped.
 */
internal class RouteProgressWatchdog<R>(
    private val minimumProgressDistanceSquared: Double,
) {
    private val states: MutableMap<UUID, State<R>> = mutableMapOf()

    fun hasStalled(npcUuid: UUID, route: R, position: NpcPosition, goal: NpcPosition, timeoutTicks: Int): Boolean {
        val distanceSquared = horizontalDistanceSquared(position, goal)
        val current = states[npcUuid]
        if (current == null || current.route != route || current.goal != goal) {
            states[npcUuid] = State(route, goal, distanceSquared, timeoutTicks)
            return false
        }
        if (distanceSquared <= current.bestHorizontalDistanceSquared - minimumProgressDistanceSquared) {
            current.bestHorizontalDistanceSquared = distanceSquared
            current.noProgressTicks = 0
            return false
        }
        current.noProgressTicks += 1
        return current.noProgressTicks >= current.timeoutTicks
    }

    fun status(npcUuid: UUID): Progress? = states[npcUuid]?.let { state ->
        Progress(state.noProgressTicks, state.timeoutTicks)
    }

    fun clear(npcUuid: UUID) {
        states.remove(npcUuid)
    }

    private fun horizontalDistanceSquared(first: NpcPosition, second: NpcPosition): Double {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    data class Progress(val noProgressTicks: Int, val timeoutTicks: Int)

    private data class State<R>(
        val route: R,
        val goal: NpcPosition,
        var bestHorizontalDistanceSquared: Double,
        val timeoutTicks: Int,
        var noProgressTicks: Int = 0,
    )
}
