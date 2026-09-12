package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatPolicyWorld
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class WorkReactionPolicyTest {
    private val snapshot = decisionContext(health = 1.0).snapshot
    private fun primary() = TaskRecord.start(snapshot.npcUuid, NavigateTaskDefinition(snapshot.dimensionId,
        NpcPosition(12.0, 64.0, 0.0), budget = TaskBudget(ticks = 600)), emptyList())
    private fun protect(subject: UUID) = TaskReactionPolicy(TaskReactionMode.PROTECT_UNIT, anchor = snapshot.position, subjectUuid = subject)
    private fun subject(world: CombatPolicyWorld, attacker: UUID, age: Long = 0): NpcEntityObservation {
        val subject = world.enemy(2, 3.0).copy(combat = NpcEntityCombatObservation(true, false, true, lastAttackerUuid = attacker, lastAttackAgeTicks = age))
        world.entities[subject.uuid] = subject
        return subject
    }

    @Test fun protectionSelectionIsReadOnlyAndConsumedHitSurvivesTaskOnlyReload() {
        val world = CombatPolicyWorld(); val attacker = world.enemy(3); val subject = subject(world, attacker.uuid)
        val record = primary(); record.reaction.policy = protect(subject.uuid)
        val before = TaskCodec.write(record)
        val candidate = assertNotNull(TaskReactionSelection.candidate(record, snapshot, world))
        assertEquals(attacker.uuid, candidate.attacker)
        assertEquals(before, TaskCodec.write(record))
        assertTrue(TaskCombatReactions.requested(record, snapshot, world))
        TaskReactionSelection.consume(record, snapshot, candidate)
        val restored = TaskCodec.read(TaskCodec.write(record))
        assertEquals(TaskProtectionHit(subject.uuid, attacker.uuid, snapshot.gameTime), restored.reaction.consumedProtectionHit)
        world.entities[subject.uuid] = subject.copy(combat = subject.combat?.copy(lastAttackAgeTicks = 7))
        assertFalse(TaskCombatReactions.requested(restored, snapshot.copy(gameTime = snapshot.gameTime + 7), world))
        world.entities[subject.uuid] = subject.copy(combat = subject.combat?.copy(lastAttackAgeTicks = 0))
        assertTrue(TaskCombatReactions.requested(restored, snapshot.copy(gameTime = snapshot.gameTime + 8), world))
    }

    @Test fun repeatedProtectedHitsCannotRestartActiveCombatOrReplayAfterItsCooldown() {
        val world = CombatPolicyWorld(); val first = world.enemy(3); val next = world.enemy(4); val subject = subject(world, first.uuid)
        val record = primary(); record.reaction.policy = protect(subject.uuid)
        TaskReactionSelection.consume(record, snapshot, assertNotNull(TaskReactionSelection.candidate(record, snapshot, world)))
        assertNull(record.interrupt(AttackTaskDefinition(snapshot.dimensionId, first.uuid, snapshot.position)))
        record.reaction.activeFrame = record.active.id
        val activeId = record.active.id
        world.entities[subject.uuid] = subject.copy(combat = subject.combat?.copy(lastAttackerUuid = next.uuid, lastAttackAgeTicks = 0))
        val later = snapshot.copy(gameTime = snapshot.gameTime + 10)
        TaskCombatReactions.observe(record, later, world)
        record.advanceTime(10)
        assertFalse(TaskCombatReactions.requested(record, later, world))
        assertEquals(activeId, record.active.id)
        val restored = TaskCodec.read(TaskCodec.write(record))
        assertEquals(activeId, restored.reaction.activeFrame)
        restored.endCombat(TaskStatus.CANCELLED, TaskReason.TARGET_UNAVAILABLE, 0, "unloaded")
        assertNull(restored.reaction.activeFrame)
        restored.advanceTime(40)
        assertEquals(550, restored.primary.remainingTicks)
        world.entities[subject.uuid] = subject.copy(combat = subject.combat?.copy(lastAttackerUuid = next.uuid, lastAttackAgeTicks = 40))
        assertFalse(TaskCombatReactions.requested(restored, later.copy(gameTime = later.gameTime + 40), world))
        assertEquals(1, restored.completedInterruptions)
        assertEquals(0, restored.lastCombat?.confirmedKills)
    }

    @Test fun areaSearchIsExplicitStaggeredAndCannotInterruptCombatOrEscapeItsBoundary() {
        val delegate = CombatPolicyWorld(); delegate.enemy(4, 0.5, "minecraft:cow"); val included = delegate.enemy(3, 2.0)
        var searches = 0
        val world = object : NpcWorldView by delegate {
            override fun queryEntities(query: NpcEntityQuery): List<NpcEntityObservation> { searches++; return delegate.queryEntities(query) }
        }
        val record = primary(); record.reaction.policy = TaskReactionPolicy(TaskReactionMode.AREA, anchor = snapshot.position,
            filter = NpcEntityTypeFilter.of(setOf("minecraft:zombie")))
        val found = (100L..109L).mapNotNull { tick -> TaskReactionSelection.candidate(record, snapshot.copy(gameTime = tick), world) }
        assertEquals(1, searches); assertEquals(listOf(included.uuid), found.map { it.attacker })
        val scan = snapshot.copy(gameTime = 100L + Math.floorMod(snapshot.npcUuid.hashCode(), 10))
        assertNull(TaskReactionSelection.candidate(record, scan.copy(position = NpcPosition(25.0, 64.0, 0.0)), world))
        record.reaction.cooldownRemaining = 1
        assertNull(TaskReactionSelection.candidate(record, scan, world))
        record.reaction.cooldownRemaining = 0
        assertNull(record.interrupt(AttackTaskDefinition(snapshot.dimensionId, included.uuid, snapshot.position)))
        assertNull(TaskReactionSelection.candidate(record, scan, world))
        assertEquals(1, searches)
        assertEquals(record.reaction.policy, TaskCodec.read(TaskCodec.write(record)).reaction.policy)
    }

    @Test fun missingMovedOrReboundSubjectDoesNotAuthorizeAnArbitraryNearbyEnemy() {
        val world = CombatPolicyWorld(); val attacker = world.enemy(3); val subject = subject(world, attacker.uuid)
        val record = primary(); record.reaction.policy = protect(subject.uuid)
        world.entities.remove(subject.uuid)
        assertFalse(TaskCombatReactions.requested(record, snapshot, world))
        world.entities[subject.uuid] = subject.copy(position = NpcPosition(30.0, 64.0, 0.0))
        assertFalse(TaskCombatReactions.requested(record, snapshot, world))
        world.entities[subject.uuid] = subject
        record.reaction.policy = protect(subject.uuid).copy(mode = TaskReactionMode.PROTECT_SUMMONER)
        assertFalse(TaskCombatReactions.requested(record, snapshot.copy(summonerUuid = UUID(0, 99)), world))
        assertTrue(TaskCombatReactions.requested(record, snapshot.copy(summonerUuid = subject.uuid), world))
        record.pause()
        TaskCombatReactions.observe(record, snapshot, world)
        assertEquals(snapshot.gameTime, record.reaction.consumedProtectionHit?.gameTime)
        record.resume()
        assertFalse(TaskCombatReactions.requested(record, snapshot.copy(summonerUuid = subject.uuid), world))
    }

    @Test fun futureModesAndForgedWatermarksCannotHideInOldOrCurrentSaves() {
        val record = primary(); record.reaction.policy = protect(UUID(0, 2))
        record.reaction.consumedProtectionHit = TaskProtectionHit(UUID(0, 2), UUID(0, 3), 100)
        val valid = TaskCodec.write(record)
        assertEquals(record.reaction.policy, TaskCodec.read(valid).reaction.policy)
        for (version in 1..4) assertFailsWith<IllegalArgumentException> { TaskCodec.read(valid, version) }
        for (alter in listOf<(CompoundTag) -> Unit>(
            { it.getCompound("consumedProtectionHit").putUUID("subject", UUID(0, 8)) },
            { it.getCompound("consumedProtectionHit").putLong("gameTime", -1) },
            { it.putUUID("activeFrame", UUID(0, 8)) },
            { it.putString("mode", "AREA") },
            { it.getCompound("anchor").putDouble("x", Double.NaN) },
        )) {
            val changed = valid.copy(); alter(changed.getCompound("reaction"))
            assertFailsWith<IllegalArgumentException> { TaskCodec.read(changed) }
        }
    }

    @Test fun interruptionBudgetCannotBeBypassedByRepeatedAreaScans() {
        val world = CombatPolicyWorld(); world.enemy(3)
        val record = primary(); record.reaction.policy = TaskReactionPolicy(TaskReactionMode.AREA, anchor = snapshot.position,
            filter = NpcEntityTypeFilter.of(setOf("minecraft:zombie")))
        record.completedInterruptions = 32
        val scan = snapshot.copy(gameTime = 100L + Math.floorMod(snapshot.npcUuid.hashCode(), 10))
        assertNull(TaskReactionSelection.candidate(record, scan, world))
        assertEquals(600, record.primary.remainingTicks)
        assertNotNull(protect(snapshot.npcUuid).copy(anchor = null).validationProblem())
        assertNotNull(TaskReactionPolicy(TaskReactionMode.AREA, anchor = snapshot.position).validationProblem())
        assertNotNull(TaskReactionPolicy(subjectUuid = UUID(0, 2)).validationProblem())
    }
    @Test fun runtimeAreaSearchQueuesAcrossTicksAndReusesOnlyTheCurrentCapturedReadiness() {
        val delegate = CombatPolicyWorld(); val target = delegate.enemy(3)
        val metrics = io.samcnpc.behavior.runtime.BehaviorWorkMetrics()
        val budget = io.samcnpc.behavior.kernel.work.PlanningBudget(unitsPerTick = 100, maxSlice = 100)
        val record = primary(); record.reaction.policy = TaskReactionPolicy(TaskReactionMode.AREA, anchor = snapshot.position,
            filter = NpcEntityTypeFilter.of(setOf("minecraft:zombie")))
        fun view(tick: Long): NpcWorldView {
            budget.advance(tick)
            return io.samcnpc.behavior.runtime.MeasuredWorldView(delegate, metrics) { units, kind ->
                budget.acquire(snapshot.npcUuid, tick, units, kind)
            }
        }
        TaskReactionReadiness.clear()
        val before = TaskCodec.write(record)
        assertNull(TaskReactionReadiness.candidate(record, snapshot.copy(gameTime = 100), view(100)))
        val candidate = assertNotNull(TaskReactionReadiness.candidate(record, snapshot.copy(gameTime = 101), view(101)))
        assertEquals(target.uuid, candidate.attacker)
        assertEquals(candidate, TaskReactionReadiness.candidate(record, snapshot.copy(gameTime = 101), view(101)))
        assertEquals(1L, metrics.snapshot().observations[io.samcnpc.behavior.runtime.BehaviorObservationKind.ENTITIES])
        for (tick in 102L..110L) assertNull(TaskReactionReadiness.candidate(record, snapshot.copy(gameTime = tick), view(tick)))
        assertNull(TaskReactionReadiness.candidate(record, snapshot.copy(gameTime = 111), view(111)))
        assertNotNull(TaskReactionReadiness.candidate(record, snapshot.copy(gameTime = 112), view(112)))
        assertEquals(2L, metrics.snapshot().observations[io.samcnpc.behavior.runtime.BehaviorObservationKind.ENTITIES])
        assertEquals(before, TaskCodec.write(record))
        record.pause()
        assertNull(TaskReactionReadiness.candidate(record, snapshot.copy(gameTime = 112), view(112)))
        TaskReactionReadiness.clear()
    }

}
