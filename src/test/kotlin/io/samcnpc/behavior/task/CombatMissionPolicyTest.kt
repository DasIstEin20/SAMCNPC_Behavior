package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.*
import io.samcnpc.behavior.command.TaskMissionCommands
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class CombatMissionPolicyTest {
    private val snapshot = decisionContext(health = 1.0).snapshot
    private val anchor = snapshot.position

    @Test fun protectionConsumesOneAcceptedThreatAndRetainsItsTargetDuringFurtherHits() {
        val world = CombatPolicyWorld(); val first = world.enemy(3); val other = world.enemy(4)
        val subject = world.enemy(2).copy(isPlayer = true, combat = NpcEntityCombatObservation(true, false, true, lastAttackerUuid = first.uuid, lastAttackAgeTicks = 0))
        world.entities[subject.uuid] = subject
        val definition = DefendTaskDefinition(snapshot.dimensionId, anchor, 24.0, subject.uuid)
        val state = CombatTaskState(dutyTicks = definition.dutyTicks)
        assertEquals(first.uuid, CombatMissionTargets.choose(definition, state, snapshot, world)?.uuid)
        assertNull(CombatMissionTargets.choose(definition, state, snapshot, world))
        state.selectedTarget = first.uuid
        world.entities[subject.uuid] = subject.copy(combat = subject.combat?.copy(lastAttackerUuid = other.uuid, lastAttackAgeTicks = 0))
        assertEquals(first.uuid, CombatMissionTargets.choose(definition, state, snapshot.copy(gameTime = 101), world)?.uuid)
        state.selectedTarget = null
        assertEquals(other.uuid, CombatMissionTargets.choose(definition, state, snapshot.copy(gameTime = 101), world)?.uuid)
        val restored = CombatTacticsCodec.readState(CombatTacticsCodec.writeState(state))
        assertNull(CombatMissionTargets.choose(definition, restored, snapshot.copy(gameTime = 106), world.apply {
            entities[subject.uuid] = checkNotNull(entities[subject.uuid]).copy(combat = subject.combat?.copy(lastAttackerUuid = other.uuid, lastAttackAgeTicks = 5))
        }))
    }
    @Test fun designatedSupportEnemyIsSeparateFromProtectingSummonerAndHasNoReplacement() {
        val world = CombatPolicyWorld(); val designated = world.enemy(4); world.enemy(3)
        val definition = PatrolTaskDefinition(snapshot.dimensionId, anchor, 24.0, listOf(anchor), reaction = PatrolReaction.SUPPORT, supportTargetUuid = designated.uuid)
        val state = CombatTaskState()
        assertNull(definition.validationProblem())
        assertEquals(designated.uuid, CombatMissionTargets.choose(definition, state, snapshot, world)?.uuid)
        world.entities.remove(designated.uuid)
        assertNull(CombatMissionTargets.choose(definition, state, snapshot, world))
        val invalid = PatrolTaskDefinition(snapshot.dimensionId, anchor, 24.0, listOf(anchor), reaction = PatrolReaction.SUPPORT, subjectUuid = designated.uuid)
        assertNotNull(invalid.validationProblem())
    }
    @Test fun tagsAndTypesAreAUnionAndCannotBypassSummonerPlayerPermissionOrBounds() {
        val world = CombatPolicyWorld(); val zombie = world.enemy(5, 2.0); val raider = world.enemy(3, 2.0, "minecraft:pillager")
        world.enemy(4, 0.5, "minecraft:cow"); world.tags[raider.uuid] = setOf("minecraft:raiders")
        val filter = TaskMissionCommands.parseFilter("minecraft:zombie,#minecraft:raiders")
        val area = CombatTargetSelector.Area(anchor, 24.0)
        assertEquals(raider.uuid, CombatTargetSelector.filtered(snapshot, world, area, filter)?.uuid)
        assertEquals(zombie.uuid, CombatTargetSelector.filtered(snapshot, world, area, filter, retained = zombie.uuid)?.uuid)
        world.entities[raider.uuid] = raider.copy(isPlayer = true)
        assertEquals(zombie.uuid, CombatTargetSelector.filtered(snapshot, world, area, filter)?.uuid)
        world.entities[zombie.uuid] = zombie.copy(position = NpcPosition(26.0, 64.0, 0.0))
        assertNull(CombatTargetSelector.filtered(snapshot, world, area, filter))
        assertEquals(raider.uuid, CombatTargetSelector.filtered(snapshot, world, area, filter, allowPlayers = true)?.uuid)
        world.entities[raider.uuid] = raider.copy(combat = raider.combat?.copy(permitted = false))
        assertNull(CombatTargetSelector.filtered(snapshot, world, area, filter, allowPlayers = true))
    }
    @Test fun routeAndFilterInputIsBoundedImmutableData() {
        val route = TaskMissionCommands.parseRoute("0,64,0;6.5,64,-2")
        assertEquals(2, route.size)
        val mutable = route.toMutableList()
        val patrol = PatrolTaskDefinition(snapshot.dimensionId, anchor, 24.0, mutable)
        mutable.clear(); assertEquals(2, patrol.route.size)
        for (value in listOf("", "NaN,64,0", "1,2", (1..17).joinToString(";") { "0,64,0" })) assertFailsWith<IllegalArgumentException> { TaskMissionCommands.parseRoute(value) }
        for (value in listOf("", "minecraft:zombie,minecraft:zombie", "minecraft:zombie;kill", "#", (1..33).joinToString(",") { "test:t$it" })) assertFailsWith<IllegalArgumentException> { TaskMissionCommands.parseFilter(value) }
        assertNotNull(DefendTaskDefinition(snapshot.dimensionId, anchor, 24.0).validationProblem())
        assertNotNull(PatrolTaskDefinition(snapshot.dimensionId, anchor, 4.0, listOf(NpcPosition(6.0, 64.0, 0.0))).validationProblem())
    }
}
