package io.samcnpc.behavior.runtime

import io.samcnpc.behavior.model.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BehaviorWorkMetricsTest {
    @Test
    fun disabledProfilingDoesNotReadAClockAndCountersRemainInspectable() {
        val metrics = BehaviorWorkMetrics(false) { error("disabled profiling read the clock") }
        val started = metrics.begin()
        metrics.observed(BehaviorObservationKind.BLOCK)
        metrics.finish(null, started)
        val statistics = metrics.snapshot()
        assertEquals(1L, statistics.decisions)
        assertEquals(1L, statistics.observations[BehaviorObservationKind.BLOCK])
        assertNull(statistics.lastDecisionNanos)
        assertEquals(0L, statistics.timedDecisions)
    }

    @Test
    fun timingIncludesOnlyTheExplicitDecisionWindow() {
        var now = 100L
        val metrics = BehaviorWorkMetrics(true) { now }
        val started = metrics.begin()
        now = 130L
        metrics.finish(null, started)
        now = 9000L
        assertEquals(30L, metrics.snapshot().lastDecisionNanos)
        assertEquals(30L, metrics.snapshot().totalDecisionNanos)
    }

    @Test
    fun worldEffectsAreVisibleToLaterActionReadsAndDiagnosticSnapshotsAreDetached() {
        val position = NpcBlockPosition(1, 2, 3)
        var observation = NpcBlockObservation(position, "minecraft:stone", false, true, false)
        val delegate = object : NpcWorldView {
            override val dimensionId = "minecraft:overworld"
            override fun observeBlock(position: NpcBlockPosition) = observation
            override fun observeBlockContainer(position: NpcBlockPosition): NpcBlockContainerObservation? = error("unexpected")
            override fun observeEntity(uuid: UUID): NpcEntityObservation? = error("unexpected")
            override fun queryEntities(query: NpcEntityQuery): List<NpcEntityObservation> = error("unexpected")
            override fun raycast(request: NpcRaycastRequest): NpcRaycastResult = error("unexpected")
        }
        val metrics = BehaviorWorkMetrics()
        val world = MeasuredWorldView(delegate, metrics)
        var started = metrics.begin()
        assertEquals("minecraft:stone", world.observeBlock(position)?.blockId)
        observation = NpcBlockObservation(position, "minecraft:air", true, false, false)
        assertEquals("minecraft:air", world.observeBlock(position)?.blockId)
        metrics.finish(null, started)
        val before = metrics.snapshot()
        started = metrics.begin()
        world.observeBlock(position)
        metrics.finish(null, started)
        assertEquals(2L, before.observations[BehaviorObservationKind.BLOCK])
        assertEquals(3L, metrics.snapshot().observations[BehaviorObservationKind.BLOCK])
        assertEquals(1L, metrics.snapshot().lastObservations[BehaviorObservationKind.BLOCK])
        assertFailsWith<UnsupportedOperationException> {
            (before.observations as MutableMap)[BehaviorObservationKind.BLOCK] = 100L
        }
    }

    @Test
    fun ruleCooldownsAndChannelRejectionsHaveDifferentWorkCounts() {
        fun action(id: String, channel: BehaviorChannel) =
            CompiledAction(id, { _, _, _ -> error("test executor handles effects") }, setOf(channel))
        val pack = CompiledPack("test:counts", "", 0, BehaviorChannel.entries.toSet(), listOf(
            CompiledRule("a", 10, 0, ConditionExpression.Test("test:yes") { true }, listOf(
                action("test:look", BehaviorChannel.LOOK), action("test:move", BehaviorChannel.MOVEMENT))),
            CompiledRule("b", 0, 0, ConditionExpression.Test("test:yes") { true }, listOf(
                action("test:other_look", BehaviorChannel.LOOK))),
        ))
        val plan = BehaviorDecisionPlan(listOf(pack))
        val cooldowns = mutableMapOf<String, Long>()
        val metrics = BehaviorWorkMetrics()
        var started = metrics.begin()
        val first = plan.tick(decisionContext(), cooldowns) { NpcActionResult.running("active") }
        metrics.finish(first, started)
        assertEquals(2, first.evaluatedRules)
        assertEquals(2, first.matchedRules)
        assertEquals(3, first.eligibleIntents)
        assertEquals(2, first.outcomes.size)
        cooldowns["test:counts/a"] = 200L
        started = metrics.begin()
        val second = plan.tick(decisionContext(101), cooldowns) { NpcActionResult.rejected("blocked") }
        metrics.finish(second, started)
        assertEquals(1, second.evaluatedRules)
        assertEquals(1, second.eligibleIntents)
        assertEquals("test:counts/b: blocked", second.lastProblem)
        val total = metrics.snapshot()
        assertEquals(3L, total.evaluatedRules)
        assertEquals(4L, total.eligibleIntents)
        assertEquals(3L, total.executedIntents)
        assertFailsWith<UnsupportedOperationException> { (first.outcomes as MutableList).clear() }
    }
}
