package io.samcnpc.behavior.runtime

import com.google.gson.JsonParser
import io.samcnpc.behavior.model.BehaviorReadContext
import io.samcnpc.core.api.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class FollowMovementTest {
    private val settings = FollowMovement.Settings(1.0F, 3.0, 5.0)
    @AfterEach fun clear() { FollowMovement.clearAll() }

    @Test fun deadZoneHoldsUntilTheOuterRadiusAndUsesVerticalDistance() {
        val body = Body()
        run(body, 0, NpcPosition(6.0, 64.0, 0.0))
        assertNotNull(body.current.navigation)
        body.current = body.current.copy(position = NpcPosition(4.0, 64.0, 0.0))
        run(body, 1, NpcPosition(6.0, 64.0, 0.0))
        assertNull(body.current.navigation)
        val calls = body.requests.size
        run(body, 2, NpcPosition(8.0, 64.0, 0.0))
        assertEquals(calls, body.requests.size)
        run(body, 3, NpcPosition(4.0, 70.0, 0.0))
        assertEquals(NpcPosition(4.0, 70.0, 0.0), body.current.navigation?.request?.position)
    }

    @Test fun movedDestinationIsReplannedOnlyAtTheBoundedCadence() {
        val body = Body()
        run(body, 0, NpcPosition(8.0, 64.0, 0.0))
        val first = body.current.navigation?.actionId
        run(body, 1, NpcPosition(9.0, 64.0, 0.0))
        assertEquals(first, body.current.navigation?.actionId)
        assertEquals(8.0, body.requests.last().position.x)
        run(body, 20, NpcPosition(9.0, 64.0, 0.0))
        assertNotEquals(first, body.current.navigation?.actionId)
        assertEquals(9.0, body.requests.last().position.x)
    }

    @Test fun movingTargetCannotRefreshAStuckBodyForeverAndExhaustionWaits() {
        val body = Body()
        val attemptFailures = mutableListOf<String>()
        for (tick in 0..500) {
            val target = NpcPosition(10.0 + minOf(tick, 200) * 0.03, 64.0, 0.0)
            val result = run(body, tick.toLong(), target)
            if (result.detail.contains("; attempt ")) attemptFailures.add(result.detail)
        }
        assertEquals(3, attemptFailures.size)
        assertTrue(attemptFailures.last().endsWith("attempt 3/3"))
        assertNull(body.current.navigation)
        val calls = body.requests.size
        repeat(20) { run(body, 501L + it, NpcPosition(16.0, 64.0, 0.0)) }
        assertEquals(calls, body.requests.size)
        run(body, 530, NpcPosition(18.0, 64.0, 0.0))
        assertNotNull(body.current.navigation)
    }

    @Test fun absentSummonerStopsImmediatelyAndCannotReuseThePriorRoute() {
        val body = Body()
        run(body, 0, NpcPosition(8.0, 64.0, 0.0))
        val prior = body.current.navigation?.actionId
        val result = FollowMovement.tick(body, BehaviorReadContext(body.current, null, null), settings)
        assertEquals(NpcActionStatus.RUNNING, result.status)
        assertNull(body.current.navigation)
        run(body, 1, NpcPosition(8.0, 64.0, 0.0))
        assertNotEquals(prior, body.current.navigation?.actionId)
    }

    @Test fun oldArgumentsRemainValidButInvertedHysteresisAndExtraDataAreRejected() {
        val definition = FollowMovement.definition()
        fun problem(json: String) = definition.validateArgs(JsonParser.parseString(json).asJsonObject)
        assertNull(problem("""{"speed":1.0,"stopDistance":3.0}"""))
        assertNotNull(problem("""{"speed":1.0,"stopDistance":3.0,"startDistance":2.0}"""))
        assertNotNull(problem("""{"speed":1.0,"stopDistance":3.0,"url":"https://example.invalid"}"""))
    }

    private fun run(body: Body, tick: Long, destination: NpcPosition): NpcActionResult {
        body.current = body.current.copy(gameTime = tick)
        val subject = NpcEntityObservation(UUID(0, 2), "minecraft:player", destination, NpcVector(0.0, 0.0, 0.0), true, true, 1.0)
        return FollowMovement.tick(body, BehaviorReadContext(body.current, subject, null), settings)
    }
    private class Body : TestNpcFacade() {
        var current = decisionContext().snapshot
        val requests = mutableListOf<NpcNavigationRequest>()
        override fun snapshot() = current
        override fun lookAtEntity(entityUuid: UUID) = NpcActionResult.succeeded("look")
        override fun stopControl(): NpcActionResult {
            current = current.copy(navigation = null, control = null)
            return NpcActionResult.succeeded("stopped")
        }
        override fun navigateTo(request: NpcNavigationRequest): NpcActionResult {
            requests.add(request)
            val old = current.navigation
            val id = if (old?.request == request) old.actionId else UUID.randomUUID()
            current = current.copy(navigation = NpcNavigationState(id, request, current.gameTime + request.leaseTicks, 8.0, 0))
            return NpcActionResult.running("route", id, NpcActionChannel.LOCOMOTION)
        }
    }
}
