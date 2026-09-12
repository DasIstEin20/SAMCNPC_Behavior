package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.behavior.combat.CombatPolicyWorld
import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class PassageYieldingTest {
    private val left = NpcPosition(0.5, 64.0, 0.5)
    private val right = NpcPosition(3.5, 64.0, 0.5)
    private val first = UUID(0, 1); private val second = UUID(0, 2)
    @Test fun stablePrioritySelectsOnlyOneLoserRegardlessOfRouteAnnouncementOrder() {
        for (reverse in listOf(false, true)) {
            val routes = PassageIntentRegistry()
            val order = if (reverse) listOf(second, first) else listOf(first, second)
            for (npc in order) routes.update(npc, npc, "minecraft:overworld", 0, if (npc == first) left else right,
                if (npc == first) NpcPosition(10.0, 64.0, 0.5) else NpcPosition(-10.0, 64.0, 0.5))
            val a = assertNotNull(routes.peer(first)); val b = assertNotNull(routes.peer(second))
            assertFalse(routes.yieldsTo(a, b, right)); assertTrue(routes.yieldsTo(b, a, left))
            assertFalse(routes.yieldsTo(b, a, left.copy(y = 68.0)))
        }
    }
    @Test fun staleTaskReleaseCannotRemoveANewerRouteAndIdlePeersExpire() {
        val routes = PassageIntentRegistry(1)
        assertNotNull(routes.update(first, first, "world", 0, left, right))
        assertNull(routes.update(second, second, "world", 0, right, left))
        assertNotNull(routes.update(first, second, "world", 1, left, right))
        routes.release(first, first); assertNotNull(routes.peer(first))
        assertNotNull(routes.update(second, second, "world", 22, right, left)); assertNull(routes.peer(first))
        routes.update(first, first, "world", 0, left, right); assertNull(routes.peer(second))
    }
    @Test fun sideCellMustBeSupportedDryUnoccupiedAndInsideTheSuppliedBoundary() {
        val base = CombatPolicyWorld()
        val world = object : NpcWorldView by base {
            override fun observeStandingSpace(feet: NpcPosition) = NpcStandingSpaceObservation(feet,
                clear = feet.x >= 0, supported = feet.z > 0, inFluid = feet.x > 1)
        }
        val destination = NpcPosition(-10.0, 64.0, 0.5)
        val selected = assertNotNull(PassageYielding.chooseStanding(left, right, destination, world, emptyList()) { it.z <= 2.5 })
        assertTrue(selected.x in 0.0..1.0 && selected.z == 2.5)
        assertNull(PassageYielding.chooseStanding(left, right, destination, world, emptyList()) { it.z <= 1.0 })
        val occupant = base.enemy(7).copy(position = selected)
        assertNull(PassageYielding.chooseStanding(left, right, destination, world, listOf(occupant)) { it == selected })
    }
    @Test fun apparentlySafeFloorBehindAWallCannotWinOverTheConnectedBay() {
        val world = object : NpcWorldView by CombatPolicyWorld() {
            override fun observeStandingSpace(feet: NpcPosition) = NpcStandingSpaceObservation(feet,
                clear = feet.z != -0.5, supported = true, inFluid = false)
        }
        val selected = assertNotNull(PassageYielding.chooseStanding(left, right, NpcPosition(-10.0,64.0,0.5), world, emptyList()) { true })
        assertTrue(selected.z > 1.5, "unreachable negative-z floor selected: $selected")
    }
    @Test fun realArrivalOpensOnePassingWindowButCannotRenewItsDeadline() {
        val blocked = YieldWindow(100)
        assertEquals(160, blocked.deadline)
        val reached = YieldWindow(100)
        reached.observeArrival(150)
        assertEquals(210, reached.deadline)
        reached.observeArrival(190)
        assertEquals(150, reached.arrivedAt); assertEquals(210, reached.deadline)
        assertFailsWith<IllegalArgumentException> { reached.observeArrival(210) }
    }
    @Test fun twoYieldingRequestsCannotDriveIntoTheSameInitiallyEmptySideCell() {
        PassageYielding.clear()
        val third = UUID(0, 3); val facts = CombatPolicyWorld()
        facts.entities[first] = facts.enemy(1).copy(position = right)
        facts.entities[second] = facts.enemy(2).copy(position = left)
        facts.entities[third] = facts.enemy(3).copy(position = left)
        val world = object : NpcWorldView by facts {
            override fun observeStandingSpace(feet: NpcPosition) = NpcStandingSpaceObservation(feet, true, true, false)
        }
        class Body(override val npcUuid: UUID, val position: NpcPosition) : TestNpcFacade() {
            var tick = 0L; val routes = mutableListOf<NpcPosition>()
            override fun snapshot() = decisionContext(tick).snapshot.copy(npcUuid = npcUuid, position = position)
            override fun stopControl() = NpcActionResult.succeeded("test stop")
            override fun navigateTo(request: NpcNavigationRequest): NpcActionResult { routes.add(request.position); return NpcActionResult.accepted("test only", UUID.randomUUID()) }
        }
        val winner = Body(first, right); val a = Body(second, left); val b = Body(third, left)
        for (tick in 0L..15L) {
            for (body in listOf(winner, a, b)) {
                body.tick = tick
                val target = NpcPosition(if (body === winner) -10.0 else 10.0, 64.0, 0.5)
                PassageYielding.tick(body.npcUuid, body, world, NpcNavigationRequest(target)) { true }
            }
            if (a.routes.isNotEmpty() && b.routes.isNotEmpty()) break
        }
        val aCell = assertNotNull(a.routes.firstOrNull()); val bCell = assertNotNull(b.routes.firstOrNull())
        val dx = aCell.x - bCell.x; val dz = aCell.z - bCell.z
        assertTrue(dx * dx + dz * dz >= 1.5 * 1.5)
        assertEquals(1, a.routes.distinct().size); assertEquals(1, b.routes.distinct().size)
        PassageYielding.clear()
    }
    @Test fun unavailableSideSpaceEndsAtTheOriginalDeadlineWithoutInventingMovement() {
        PassageYielding.clear()
        val world = CombatPolicyWorld()
        world.entities[first] = world.enemy(1).copy(position = left)
        world.entities[second] = world.enemy(2).copy(position = right)
        class Body(override val npcUuid: UUID, val position: NpcPosition) : TestNpcFacade() {
            var tick = 0L; var moves = 0
            override fun snapshot() = decisionContext(tick).snapshot.copy(npcUuid = npcUuid, position = position)
            override fun stopControl() = NpcActionResult.succeeded("test stop")
            override fun navigateTo(request: NpcNavigationRequest): NpcActionResult { moves++; return NpcActionResult.accepted("test only", UUID.randomUUID()) }
        }
        val a = Body(first, left); val b = Body(second, right)
        for (tick in 0L..60L) {
            a.tick = tick; b.tick = tick
            val winner = PassageYielding.tick(first, a, world, NpcNavigationRequest(NpcPosition(10.0,64.0,0.5))) { false }
            if (tick == 0L || tick == 60L) assertIs<PassageYielding.Result.Proceed>(winner) else assertIs<PassageYielding.Result.Handling>(winner)
            val result = PassageYielding.tick(second, b, world, NpcNavigationRequest(NpcPosition(-10.0,64.0,0.5))) { false }
            if (tick < 60) assertIs<PassageYielding.Result.Handling>(result) else assertIs<PassageYielding.Result.Failed>(result)
        }
        assertEquals(0, a.moves); assertEquals(0, b.moves); assertFalse(PassageYielding.isYielding(second))
        PassageYielding.clear()
    }
}
