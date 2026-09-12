package io.samcnpc.behavior.kernel.work

import io.samcnpc.core.api.NpcBlockPosition
import java.util.UUID
import kotlin.math.ceil

/** One-tick collection, then oldest-waiter/UUID arbitration. No event arrival order grants a lease. */
internal class SpatialWorkClaimKernel(
    private val leaseTicks: Long = 100,
    private val separationBlocks: Double = 6.0,
    private val maxEntries: Int = 4096,
) {
    private data class Key(val dimension: String, val anchor: NpcBlockPosition)
    private data class Request(val npc: UUID, val task: UUID, val key: Key, val firstTick: Long, var lastTick: Long)
    private data class Claim(val npc: UUID, val task: UUID, val key: Key, var expires: Long, var announced: Boolean = false)
    private data class Bucket(val dimension: String, val x: Int, val y: Int, val z: Int)
    private val claims = mutableMapOf<UUID, Claim>()
    private val requests = mutableMapOf<UUID, Request>()
    private val buckets = mutableMapOf<Bucket, MutableSet<UUID>>()
    private val span = maxOf(1, ceil(separationBlocks).toInt())
    private var lastTick: Long? = null
    init {
        require(leaseTicks in 1..1200 && separationBlocks.isFinite() && separationBlocks in 0.0..64.0 && maxEntries in 1..4096)
    }

    fun renewOrClaim(npcUuid: UUID, dimensionId: String, anchor: NpcBlockPosition, gameTime: Long, taskId: UUID = npcUuid): Result {
        require(gameTime in 0..Long.MAX_VALUE - leaseTicks)
        advance(gameTime)
        val key = Key(dimensionId, anchor)
        val claim = claims[npcUuid]
        if (claim != null && claim.task == taskId && claim.key == key) {
            claim.expires = gameTime + leaseTicks
            val result = if (claim.announced) Result.Held else Result.Acquired
            claim.announced = true
            return result
        }
        if (claim != null) removeClaim(npcUuid)
        val previous = requests[npcUuid]
        if (previous?.task == taskId && previous.key == key) previous.lastTick = gameTime
        else {
            if (previous == null && claims.size + requests.size >= maxEntries) return Result.Limited
            requests[npcUuid] = Request(npcUuid, taskId, key, gameTime, gameTime)
        }
        val blocker = conflict(key)
        return if (blocker == null) Result.Pending else Result.Contested(blocker.npc, blocker.key.anchor)
    }

    fun release(npcUuid: UUID, taskId: UUID? = null) {
        if (taskId == null || claims[npcUuid]?.task == taskId) removeClaim(npcUuid)
        if (taskId == null || requests[npcUuid]?.task == taskId) requests.remove(npcUuid)
    }
    fun clear() { claims.clear(); requests.clear(); buckets.clear(); lastTick = null }

    /** A read-only local partition prevents a collector from deliberately taking its neighbor's drops. */
    fun collectionFilter(npcUuid: UUID, taskId: UUID, dimensionId: String, now: Long): (io.samcnpc.core.api.NpcPosition) -> Boolean {
        val current = claims[npcUuid]
        if (current == null || current.task != taskId || current.key.dimension != dimensionId || current.expires <= now) return { false }
        val cell = bucket(current.key)
        val radius = ceil(20.0 / span).toInt() + 1
        val nearby = mutableListOf<Pair<UUID, NpcBlockPosition>>()
        for (x in -radius..radius) for (z in -radius..radius) for (npc in buckets[Bucket(cell.dimension, cell.x + x, cell.y, cell.z + z)].orEmpty()) {
            val claim = claims[npc] ?: continue
            if (claim.expires > now) nearby.add(npc to claim.key.anchor)
        }
        return { position ->
            var best: UUID? = null
            var distance = Double.POSITIVE_INFINITY
            for ((npc, anchor) in nearby) {
                val dx = position.x - anchor.x - 0.5; val dz = position.z - anchor.z - 0.5
                val value = dx * dx + dz * dz
                if (value < distance || value == distance && (best == null || npc < best)) { best = npc; distance = value }
            }
            best == npcUuid
        }
    }


    /** A local snapshot for optional pickup: active neighboring harvest areas keep their drops. */
    fun incidentalCollectionFilter(npcUuid: UUID, taskId: UUID, dimensionId: String,
                                  origin: io.samcnpc.core.api.NpcPosition, radius: Double, now: Long): (io.samcnpc.core.api.NpcPosition) -> Boolean {
        if (claims.isEmpty()) return { true }
        val key = Key(dimensionId, NpcBlockPosition(kotlin.math.floor(origin.x).toInt(), origin.y.toInt(), kotlin.math.floor(origin.z).toInt()))
        val cell = bucket(key); val reach = ceil((radius + 12.0) / span).toInt() + 1
        val nearby = mutableListOf<Claim>()
        for (x in -reach..reach) for (z in -reach..reach) for (npc in buckets[Bucket(cell.dimension, cell.x + x, cell.y, cell.z + z)].orEmpty()) {
            val claim = claims[npc] ?: continue
            if (claim.expires > now) nearby.add(claim.copy())
        }
        return { position ->
            var best: Claim? = null; var distance = Double.POSITIVE_INFINITY
            for (claim in nearby) {
                val anchor = claim.key.anchor
                val dx = position.x - anchor.x - 0.5; val dz = position.z - anchor.z - 0.5
                if (kotlin.math.abs(dx) > 7.0 || kotlin.math.abs(dz) > 7.0 || kotlin.math.abs(position.y - anchor.y) > 12.0) continue
                val value = dx * dx + dz * dz
                if (value < distance || value == distance && (best == null || claim.npc < best.npc)) { best = claim; distance = value }
            }
            best == null || best.npc == npcUuid && best.task == taskId
        }
    }

    private fun advance(now: Long) {
        val before = lastTick
        if (before != null && now < before) clear()
        if (lastTick == now) return
        lastTick = now
        for ((npc, claim) in claims.toMap()) if (claim.expires <= now) removeClaim(npc)
        requests.entries.removeIf { now - it.value.lastTick > leaseTicks }
        val candidates = requests.values.filter { it.firstTick < now }.sortedWith(compareBy<Request> { it.firstTick }.thenBy { it.npc }.thenBy { it.task })
        for (request in candidates) {
            if (conflict(request.key) != null) continue
            val claim = Claim(request.npc, request.task, request.key, now + leaseTicks)
            claims[request.npc] = claim
            buckets.getOrPut(bucket(request.key)) { mutableSetOf() }.add(request.npc)
            requests.remove(request.npc)
        }
    }
    private fun removeClaim(npc: UUID) {
        val old = claims.remove(npc) ?: return
        val bucket = bucket(old.key)
        val entries = buckets[bucket] ?: return
        entries.remove(npc)
        if (entries.isEmpty()) buckets.remove(bucket)
    }
    private fun bucket(key: Key) = Bucket(key.dimension, Math.floorDiv(key.anchor.x, span), if (separationBlocks == 0.0) key.anchor.y else 0, Math.floorDiv(key.anchor.z, span))
    private fun conflict(key: Key): Claim? {
        val cell = bucket(key)
        var result: Claim? = null
        val radius = if (separationBlocks == 0.0) 0 else 1
        for (dx in -radius..radius) for (dz in -radius..radius) {
            for (npc in buckets[Bucket(cell.dimension, cell.x + dx, cell.y, cell.z + dz)].orEmpty()) {
                val claim = claims[npc] ?: continue
                val x = key.anchor.x.toDouble() - claim.key.anchor.x
                val z = key.anchor.z.toDouble() - claim.key.anchor.z
                val intersects = if (separationBlocks == 0.0) key.anchor == claim.key.anchor else x * x + z * z < separationBlocks * separationBlocks
                if (intersects && (result == null || claim.npc < result.npc)) result = claim
            }
        }
        return result
    }
    internal sealed interface Result {
        data object Pending : Result
        data object Limited : Result
        data object Acquired : Result
        data object Held : Result
        data class Contested(val holderNpcUuid: UUID, val holderAnchor: NpcBlockPosition) : Result
    }
}
