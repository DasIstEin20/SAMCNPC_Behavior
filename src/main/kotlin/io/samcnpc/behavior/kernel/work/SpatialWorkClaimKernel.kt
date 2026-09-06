package io.samcnpc.behavior.kernel.work

import io.samcnpc.core.api.NpcBlockPosition
import java.util.UUID

/**
 * Server-local lease registry for behavior work zones. Claims are intentionally transient: an
 * NPC's durable task can recover after a restart, while stale entity ownership never survives it.
 */
internal class SpatialWorkClaimKernel(
    private val leaseTicks: Long = DEFAULT_LEASE_TICKS,
    private val separationBlocks: Double = DEFAULT_SEPARATION_BLOCKS,
) {
    private val claimsByNpc: MutableMap<UUID, Claim> = mutableMapOf()

    fun renewOrClaim(npcUuid: UUID, dimensionId: String, anchor: NpcBlockPosition, gameTime: Long): Result {
        releaseExpired(gameTime)
        val current = claimsByNpc[npcUuid]
        if (current != null && current.dimensionId == dimensionId && current.anchor == anchor) {
            current.expiresAt = gameTime + leaseTicks
            return Result.Held
        }
        if (current != null) {
            claimsByNpc.remove(npcUuid)
        }
        val conflict = claimsByNpc.values
            .asSequence()
            .filter { it.dimensionId == dimensionId && horizontalDistanceSquared(it.anchor, anchor) < separationBlocks * separationBlocks }
            .sortedBy { it.npcUuid.toString() }
            .firstOrNull()
        if (conflict != null) {
            return Result.Contested(conflict.npcUuid, conflict.anchor)
        }
        claimsByNpc[npcUuid] = Claim(npcUuid, dimensionId, anchor, gameTime + leaseTicks)
        return Result.Acquired
    }

    fun release(npcUuid: UUID) {
        claimsByNpc.remove(npcUuid)
    }

    fun clear() {
        claimsByNpc.clear()
    }

    private fun releaseExpired(gameTime: Long) {
        claimsByNpc.entries.removeIf { (_, claim) -> claim.expiresAt < gameTime }
    }

    private fun horizontalDistanceSquared(first: NpcBlockPosition, second: NpcBlockPosition): Double {
        val dx = (first.x - second.x).toDouble()
        val dz = (first.z - second.z).toDouble()
        return dx * dx + dz * dz
    }

    internal sealed interface Result {
        data object Acquired : Result
        data object Held : Result
        data class Contested(val holderNpcUuid: UUID, val holderAnchor: NpcBlockPosition) : Result
    }

    private data class Claim(
        val npcUuid: UUID,
        val dimensionId: String,
        val anchor: NpcBlockPosition,
        var expiresAt: Long,
    )

    private companion object {
        const val DEFAULT_LEASE_TICKS = 100L
        const val DEFAULT_SEPARATION_BLOCKS = 6.0
    }
}
