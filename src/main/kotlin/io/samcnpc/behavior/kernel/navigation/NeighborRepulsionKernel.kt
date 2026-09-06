package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/**
 * A small, transient personal-space detour for cooperative workers. The nearest worker supplies
 * the preferred opposite direction; round-robin fallback keeps a surrounded worker from trying
 * one permanently blocked side forever.
 */
internal class NeighborRepulsionKernel {
    private val activeDetours: MutableMap<UUID, Detour> = mutableMapOf()
    /**
     * A completed detour must not immediately turn into another detour while the same workers
     * are still close. Normal task navigation gets control back until they truly separate.
     */
    private val awaitingSeparation: MutableSet<UUID> = mutableSetOf()
    private val nextDirectionIndex: MutableMap<UUID, Int> = mutableMapOf()

    fun nextTarget(
        npcUuid: UUID,
        gameTime: Long,
        position: NpcPosition,
        neighbors: List<NpcPosition>,
        canStandAt: (NpcBlockPosition) -> Boolean,
    ): Step? {
        val active = activeDetours[npcUuid]
        if (active != null) {
            if (gameTime <= active.expiresAt && horizontalDistanceSquared(position, blockCenter(active.target)) > ARRIVAL_DISTANCE_SQR) {
                return Step(active.target, active.direction, started = false)
            }
            activeDetours.remove(npcUuid)
            awaitingSeparation.add(npcUuid)
        }
        if (npcUuid in awaitingSeparation) {
            val stillCrowded = neighbors.any { neighbor ->
                horizontalDistanceSquared(position, neighbor) <= RELEASE_DISTANCE_SQR
            }
            if (stillCrowded) {
                return null
            }
            awaitingSeparation.remove(npcUuid)
        }
        val nearest = neighbors
            .filter { horizontalDistanceSquared(position, it) in MIN_NEIGHBOR_DISTANCE_SQR..MAX_NEIGHBOR_DISTANCE_SQR }
            .minWithOrNull(compareBy<NpcPosition> { horizontalDistanceSquared(position, it) }.thenBy { it.x }.thenBy { it.z })
            ?: return null
        val origin = NpcBlockPosition(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())
        val preferred = oppositeDirection(position, nearest)
        val rotationOffset = nextDirectionIndex[npcUuid] ?: 0
        val start = (preferred.ordinal + rotationOffset) % Direction.entries.size
        for (offset in Direction.entries.indices) {
            val index = (start + offset) % Direction.entries.size
            val direction = Direction.entries[index]
            val target = NpcBlockPosition(
                origin.x + direction.deltaX * DETOUR_DISTANCE_BLOCKS,
                origin.y,
                origin.z + direction.deltaZ * DETOUR_DISTANCE_BLOCKS,
            )
            if (!canStandAt(target)) {
                continue
            }
            nextDirectionIndex[npcUuid] = (rotationOffset + 1) % Direction.entries.size
            activeDetours[npcUuid] = Detour(target, direction, gameTime + DETOUR_TIMEOUT_TICKS)
            return Step(target, direction, started = true)
        }
        nextDirectionIndex[npcUuid] = (rotationOffset + 1) % Direction.entries.size
        return null
    }

    fun clear(npcUuid: UUID) {
        activeDetours.remove(npcUuid)
        awaitingSeparation.remove(npcUuid)
        nextDirectionIndex.remove(npcUuid)
    }

    fun clear() {
        activeDetours.clear()
        awaitingSeparation.clear()
        nextDirectionIndex.clear()
    }

    private fun oppositeDirection(position: NpcPosition, neighbor: NpcPosition): Direction {
        val dx = position.x - neighbor.x
        val dz = position.z - neighbor.z
        return if (abs(dx) >= abs(dz)) {
            if (dx >= 0.0) Direction.EAST else Direction.WEST
        } else {
            if (dz >= 0.0) Direction.SOUTH else Direction.NORTH
        }
    }

    private fun horizontalDistanceSquared(first: NpcPosition, second: NpcPosition): Double {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    private fun blockCenter(position: NpcBlockPosition): NpcPosition =
        NpcPosition(position.x + 0.5, position.y.toDouble(), position.z + 0.5)

    internal data class Step(
        val target: NpcBlockPosition,
        val direction: Direction,
        val started: Boolean,
    )

    internal enum class Direction(val deltaX: Int, val deltaZ: Int, val label: String) {
        NORTH(0, -1, "north"),
        EAST(1, 0, "east"),
        SOUTH(0, 1, "south"),
        WEST(-1, 0, "west"),
    }

    private data class Detour(
        val target: NpcBlockPosition,
        val direction: Direction,
        val expiresAt: Long,
    )

    private companion object {
        const val DETOUR_DISTANCE_BLOCKS = 3
        const val DETOUR_TIMEOUT_TICKS = 50L
        const val ARRIVAL_DISTANCE_SQR = 0.8 * 0.8
        const val MIN_NEIGHBOR_DISTANCE_SQR = 0.3 * 0.3
        const val MAX_NEIGHBOR_DISTANCE_SQR = 2.5 * 2.5
        const val RELEASE_DISTANCE_SQR = 3.0 * 3.0
    }
}
