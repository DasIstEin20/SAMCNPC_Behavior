package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** Transient recovery of one supplied route around a live body; the task deadline never resets. */
internal class LocalObstructionDetour {
    private var target: NpcPosition? = null
    private var destination: NpcPosition? = null
    private var until = 0L
    private var attempts = 0

    fun tick(npc: NpcFacade, world: NpcWorldView, request: NpcNavigationRequest,
             allowed: (NpcPosition) -> Boolean): NpcActionResult? {
        val snapshot = npc.snapshot()
        val now = snapshot.gameTime
        if (destination != request.position) { target = null; destination = request.position }
        val active = target
        if (active != null) {
            if (now >= until || now < until - 50 || distanceSquared(snapshot.position, active) <= 0.45 * 0.45 && snapshot.onGround) {
                target = null
                npc.stopControl()
                return NpcActionResult.running("local obstacle detour ended; reobserve the original destination")
            }
            val standing = world.observeStandingSpace(active)
            if (!allowed(active) || standing == null || !standing.clear || !standing.supported || standing.inFluid) {
                target = null; npc.stopControl()
                return NpcActionResult.running("local obstacle stance changed; original route retained")
            }
            return move(npc, request, active)
        }
        if (attempts >= 3 || (snapshot.navigation?.stalledTicks ?: 0) < 12) return null
        if (!BehaviorPlanning.admit(world, 164, PlanningKind.YIELDING)) return null
        val nearby = world.queryEntities(NpcEntityQuery(snapshot.position, 3.0, 16))
        if (nearby.size == 16) return null
        val bodies = nearby.filter { it.alive && it.healthFraction != null && it.uuid != npc.npcUuid && kotlin.math.abs(it.position.y - snapshot.position.y) < 2.0 }
        val blocker = bodies.filter { distanceSquared(snapshot.position, it.position) <= 2.25 * 2.25 }
            .minWithOrNull(compareBy<NpcEntityObservation> { distanceSquared(snapshot.position, it.position) }.thenBy { it.uuid }) ?: return null
        val side = PassageYielding.chooseStanding(snapshot.position, blocker.position, request.position, world, bodies, allowed) ?: return null
        attempts++
        target = side; until = now + 50
        npc.stopControl()
        return move(npc, request, side)
    }
    private fun move(npc: NpcFacade, request: NpcNavigationRequest, side: NpcPosition): NpcActionResult {
        val action = npc.navigateTo(NpcNavigationRequest(side, request.speedMultiplier, 0.4))
        if (action.status in setOf(NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED)) {
            target = null
            return NpcActionResult.running("local obstacle route rejected; bounded original recovery remains")
        }
        return NpcActionResult.running("local obstacle detour $attempts/3 toward $side; original destination retained")
    }
    private fun distanceSquared(a: NpcPosition, b: NpcPosition): Double {
        val x=a.x-b.x; val y=a.y-b.y; val z=a.z-b.z
        return x*x+y*y+z*z
    }
}
