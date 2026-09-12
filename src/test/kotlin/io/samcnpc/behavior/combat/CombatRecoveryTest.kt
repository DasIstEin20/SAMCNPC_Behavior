package io.samcnpc.behavior.combat

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class CombatRecoveryTest {
    @Test fun recoveryUsesActualHealthHysteresisAndOneCumulativeFiniteBudget() {
        val world = CombatPolicyWorld(); val target = world.enemy(3, 6.0); val npc = CombatPolicyBody(world)
        val record = TaskRecord.start(npc.npcUuid, AttackTaskDefinition(world.dimensionId, target.uuid, npc.current.position), emptyList())
        val state = checkNotNull(record.primary.combat); val execution = TaskExecution(record.id, record.active.id)
        val area = CombatTargetSelector.Area(npc.current.position, 24.0); val tactics = CombatTactics()
        npc.current = npc.current.copy(healthFraction = 0.2)
        CombatEngagement.tick(npc, world, target, area, tactics, state, execution); assertTrue(state.retreating)
        record.advanceTime(100); assertEquals(300, state.recoveryTicks)
        npc.current = npc.current.copy(healthFraction = 0.45)
        CombatEngagement.tick(npc, world, target, area, tactics, state, execution); assertTrue(state.retreating)
        CombatEngagement.tick(npc, world, target, area, tactics.copy(retreatAt = 0.0), state, execution)
        assertFalse(state.retreating); assertEquals(300, state.recoveryTicks)
        npc.current = npc.current.copy(healthFraction = 0.2)
        CombatEngagement.tick(npc, world, target, area, tactics, state, execution); assertTrue(state.retreating)
        npc.current = npc.current.copy(healthFraction = 0.7)
        CombatEngagement.tick(npc, world, target, area, tactics, state, execution); assertFalse(state.retreating)
        npc.current = npc.current.copy(healthFraction = 0.2)
        CombatEngagement.tick(npc, world, target, area, tactics, state, execution); record.advanceTime(300)
        assertEquals(TaskReason.RECOVERY_EXHAUSTED, CombatEngagement.tick(npc, world, target, area, tactics, state, execution).end)
        assertFalse(state.observedHealing); assertEquals(0, state.healingUses)
    }
    @Test fun foreignHandUseIsPreservedAndNewDamageCannotInterruptOwnedMedicine() {
        val world = CombatPolicyWorld(); val target = world.enemy(3, 6.0); val npc = CombatPolicyBody(world)
        val state = CombatTaskState(selectedTarget = target.uuid); val execution = TaskExecution(UUID.randomUUID(), UUID.randomUUID())
        val area = CombatTargetSelector.Area(npc.current.position, 24.0); val useId = UUID.randomUUID()
        npc.current = npc.current.copy(itemUse = NpcItemUseState(NpcHand.MAIN, "test:medicine", 20, 12, useId))
        CombatEngagement.tick(npc, world, target, area, CombatTactics(), state, execution)
        assertEquals(0, npc.inventoryQueries); assertEquals(0, npc.uses)
        val medicine = NpcItemKnowledge("test:medicine", setOf(NpcItemRole.OTHER), combat = NpcCombatItemFacts(healingConsumable = true, healingStrength = 1, useTicks = 32))
        npc.current = npc.current.copy(healthFraction = 0.2, equipment = npc.current.equipment.copy(mainHand = medicine), lastDamageEventId = UUID.randomUUID())
        execution.tacticalUseId = useId
        repeat(4) {
            npc.current = npc.current.copy(lastDamageEventId = UUID.randomUUID())
            CombatEngagement.tick(npc, world, target, area, CombatTactics(), state, execution)
        }
        assertEquals(4, npc.uses); assertEquals(0, npc.inventoryQueries); assertEquals(useId, execution.tacticalUseId)
    }
}
