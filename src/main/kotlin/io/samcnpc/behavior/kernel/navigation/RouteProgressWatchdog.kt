package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.core.api.NpcPosition
import java.util.UUID

/** The caller owns route/recovery policy. Repeated calls and vertical jitter are not progress. */
internal class RouteProgressWatchdog<R>(
    private val minimumProgressDistanceSquared: Double,
) {
    init {
        require(minimumProgressDistanceSquared.isFinite() && minimumProgressDistanceSquared > 0.0)
    }

    private val states: MutableMap<UUID, State<R>> = mutableMapOf()

    fun hasStalled(npcUuid: UUID, route: R, position: NpcPosition, goal: NpcPosition,
                   timeoutTicks: Int, gameTime: Long): Boolean {
        require(timeoutTicks > 0)
        val distanceSquared = horizontalDistanceSquared(position, goal)
        val current = states[npcUuid]
        if (current == null || current.route != route || current.goal != goal || gameTime < current.lastObservedTick) {
            states[npcUuid] = State(route, goal, distanceSquared, timeoutTicks, gameTime, gameTime)
            return false
        }
        if (gameTime == current.lastObservedTick) return current.noProgressTicks >= current.timeoutTicks
        current.lastObservedTick = gameTime
        if (distanceSquared <= current.bestHorizontalDistanceSquared - minimumProgressDistanceSquared) {
            current.bestHorizontalDistanceSquared = distanceSquared
            current.lastProgressTick = gameTime
            current.noProgressTicks = 0
            return false
        }
        current.noProgressTicks = (gameTime - current.lastProgressTick).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return current.noProgressTicks >= current.timeoutTicks
    }

    fun status(npcUuid: UUID): Progress? {
        val state = states[npcUuid] ?: return null
        return Progress(state.noProgressTicks, state.timeoutTicks)
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
        var lastObservedTick: Long,
        var lastProgressTick: Long,
        var noProgressTicks: Int = 0,
    )
}
