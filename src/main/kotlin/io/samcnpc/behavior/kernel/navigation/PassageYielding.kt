package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.kernel.work.SpatialWorkClaimKernel
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/** Reaching a real bay opens one separate passing window; repeated observations cannot renew it. */
internal class YieldWindow(val started: Long) {
    var arrivedAt: Long? = null
        private set
    val deadline: Long get() = arrivedAt?.let { minOf(started + 120, it + 60) } ?: (started + 60)
    fun observeArrival(now: Long) {
        require(now >= started && now < deadline)
        if (arrivedAt == null) arrivedAt = now
    }
}

/** Bounded Behavior detour around another declared route; only Core executes motion. */
internal object PassageYielding {
    sealed interface Result {
        data object Proceed : Result
        data class Handling(val action: NpcActionResult) : Result
        data class Failed(val detail: String) : Result
    }
    private data class Yield(val task: UUID, val peer: UUID, val peerTask: UUID, val started: Long,
                             val origin: NpcPosition, val destination: NpcPosition, var standing: NpcPosition? = null, var settled: Boolean = false, val window: YieldWindow = YieldWindow(started), val rejected: MutableSet<NpcPosition> = mutableSetOf())
    private val routes = PassageIntentRegistry()
    private val sideClaims = SpatialWorkClaimKernel(leaseTicks = 60, separationBlocks = 1.5)
    private val yielding = mutableMapOf<UUID, Yield>()

    fun tick(task: UUID, npc: NpcFacade, world: NpcWorldView, request: NpcNavigationRequest,
             allowed: (NpcPosition) -> Boolean): Result {
        val snapshot = npc.snapshot(); val now = snapshot.gameTime
        val route = routes.update(npc.npcUuid, task, snapshot.dimensionId, now, snapshot.position, request.position)
            ?: return Result.Failed("passage coordination capacity reached; no new detour admitted")
        var state = yielding[npc.npcUuid]
        if (state != null && (state.task != task || state.destination != request.position || now < state.started)) { dropYield(npc.npcUuid); state = null }
        val neighbors = world.queryEntities(NpcEntityQuery(snapshot.position, 4.0, 16)).filter { it.alive && it.itemStack == null }
        if (state == null) {
            // Give the selected loser room to reach its bay before the winner resumes its
            // original route. Otherwise contact pushes it away from the only nearby opening.
            val clearing = neighbors.any { entity ->
                val other = yielding[entity.uuid]
                other != null && other.peer == npc.npcUuid && other.peerTask == task && !other.settled && now < other.window.deadline
            }
            if (clearing) {
                npc.stopControl()
                return Result.Handling(NpcActionResult.running("waiting for the selected yielding NPC to clear the passage"))
            }
            val peer = neighbors.mapNotNull { entity ->
                val intent = routes.peer(entity.uuid) ?: return@mapNotNull null
                if (routes.yieldsTo(route, intent, entity.position)) intent else null
            }.minWithOrNull(compareBy<PassageIntentRegistry.Intent> { it.firstTick }.thenBy { it.npc }) ?: return Result.Proceed
            state = Yield(task, peer.npc, peer.task, now, snapshot.position, request.position)
            yielding[npc.npcUuid] = state
            npc.stopControl()
        }
        val peer = routes.peer(state.peer)
        val observedPeer = world.observeEntity(state.peer)
        val separated = observedPeer == null || !observedPeer.alive || distanceSquared(state.origin, observedPeer.position) > 6.0 * 6.0
        val passed = peer != null && observedPeer != null && passed(state.origin, observedPeer.position, peer.destination)
        if (peer == null || peer.task != state.peerTask || separated || passed) {
            dropYield(npc.npcUuid); npc.stopControl()
            return Result.Handling(NpcActionResult.running("passage cleared; resuming the same supplied destination"))
        }
        if (now >= state.window.deadline) {
            dropYield(npc.npcUuid); npc.stopControl()
            return Result.Failed("yield phase deadline exhausted; original route and task retry budget retained")
        }
        if (state.standing == null) {
            if (!BehaviorPlanning.admit(world, 48, PlanningKind.YIELDING)) return Result.Handling(
                NpcActionResult.running("yield stance planning deferred; original approach deadline retained"))
            val rejected = state.rejected
            state.standing = chooseStanding(state.origin, checkNotNull(observedPeer).position, peer.destination, world, neighbors) {
                allowed(it) && it !in rejected
            }
            if (state.standing == null) return Result.Handling(NpcActionResult.running("no permitted side stance; bounded passage wait continues"))
        }
        val destination = checkNotNull(state.standing)
        val cell = NpcBlockPosition(floor(destination.x).toInt(), destination.y.toInt(), floor(destination.z).toInt())
        when (sideClaims.renewOrClaim(npc.npcUuid, snapshot.dimensionId, cell, now, task)) {
            SpatialWorkClaimKernel.Result.Acquired, SpatialWorkClaimKernel.Result.Held -> Unit
            SpatialWorkClaimKernel.Result.Pending, SpatialWorkClaimKernel.Result.Limited -> return Result.Handling(
                NpcActionResult.running("waiting for the selected side stance reservation within the original deadline"))
            is SpatialWorkClaimKernel.Result.Contested -> {
                sideClaims.release(npc.npcUuid, task)
                state.rejected.add(destination); state.standing = null
                return Result.Handling(NpcActionResult.running("side stance already reserved; choose another permitted connected cell"))
            }
        }
        val standing = world.observeStandingSpace(destination)
        if (!allowed(destination) || standing == null || !standing.clear || !standing.supported || standing.inFluid) {
            npc.stopControl()
            return Result.Failed("yield stance became unavailable; no unpermitted fallback selected")
        }
        if (distanceSquared(snapshot.position, destination) <= 0.55 * 0.55 && snapshot.onGround) {
            state.window.observeArrival(now)
            state.settled = true
            npc.stopControl()
            return Result.Handling(NpcActionResult.running("holding side stance for the older passage request"))
        }
        state.settled = false
        val action = npc.navigateTo(NpcNavigationRequest(destination, request.speedMultiplier, 0.45))
        if (action.status in setOf(NpcActionStatus.FAILED, NpcActionStatus.REJECTED, NpcActionStatus.UNSUPPORTED)) return Result.Failed("yield route rejected: ${action.detail}")
        return Result.Handling(NpcActionResult.running("yielding to ${state.peer}; ${action.code}; deadline=${state.window.deadline - now}"))
    }

    internal fun chooseStanding(origin: NpcPosition, peer: NpcPosition, peerDestination: NpcPosition, world: NpcWorldView,
                                neighbors: List<NpcEntityObservation>, allowed: (NpcPosition) -> Boolean): NpcPosition? {
        val vx = peerDestination.x - peer.x; val vz = peerDestination.z - peer.z
        val lengthSquared = vx * vx + vz * vz
        if (lengthSquared < 0.01) return null
        val cells = linkedMapOf<Pair<Int, Int>, NpcPosition>()
        for (dx in -3..3) for (dz in -3..3) {
            if (dx * dx + dz * dz > 10) continue
            val candidate = NpcPosition(floor(origin.x) + dx + 0.5, floor(origin.y), floor(origin.z) + dz + 0.5)
            if (!allowed(candidate)) continue
            if (neighbors.any { abs(it.position.y - candidate.y) < 2 && horizontalSquared(it.position, candidate) < 1.0 }) continue
            val standing = world.observeStandingSpace(candidate) ?: continue
            if (standing.clear && standing.supported && !standing.inFluid) cells[dx to dz] = candidate
        }
        // A clear destination behind a wall is not a reachable side bay. Four-neighbor
        // connectivity also forbids diagonal corner cuts in this bounded local grid.
        val pending = ArrayDeque<Pair<Int, Int>>()
        val reached = mutableSetOf(0 to 0)
        pending.addLast(0 to 0)
        while (pending.isNotEmpty()) {
            val cell = pending.removeFirst()
            for ((dx, dz) in DIRECTIONS) {
                val next = cell.first + dx to cell.second + dz
                if (next in cells && reached.add(next)) pending.addLast(next)
            }
        }
        var best: NpcPosition? = null; var bestScore = Double.NEGATIVE_INFINITY
        for ((cell, candidate) in cells) {
            if (cell !in reached || cell == (0 to 0)) continue
            val cross = (candidate.x - peer.x) * vz - (candidate.z - peer.z) * vx
            val separation = cross * cross / lengthSquared
            if (separation < 1.15 * 1.15) continue
            val score = separation * 100 - distanceSquared(origin, candidate)
            if (score > bestScore) { best = candidate; bestScore = score }
        }
        return best
    }
    private val DIRECTIONS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    private fun passed(origin: NpcPosition, peer: NpcPosition, destination: NpcPosition): Boolean =
        (origin.x - peer.x) * (destination.x - peer.x) + (origin.z - peer.z) * (destination.z - peer.z) < -0.5
    private fun horizontalSquared(a: NpcPosition, b: NpcPosition): Double { val x = a.x - b.x; val z = a.z - b.z; return x * x + z * z }
    private fun distanceSquared(a: NpcPosition, b: NpcPosition): Double { val y = a.y - b.y; return horizontalSquared(a, b) + y * y }
    fun isYielding(npc: UUID): Boolean = npc in yielding
    fun isClearing(npc: UUID): Boolean = yielding[npc]?.window?.arrivedAt == null && npc in yielding
    fun describe(npc: UUID): String? = yielding[npc]?.let { "peer=${it.peer}; started=${it.started}; standing=${it.standing}; arrived=${it.window.arrivedAt}; deadline=${it.window.deadline}" }
    private fun dropYield(npc: UUID) { yielding.remove(npc); sideClaims.release(npc) }
    fun release(npc: UUID) { routes.release(npc); dropYield(npc) }
    fun clear() { routes.clear(); yielding.clear(); sideClaims.clear() }
}
