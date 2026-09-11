package io.samcnpc.behavior.runtime

import io.samcnpc.behavior.combat.CombatTargetSelector
import io.samcnpc.core.api.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class RetaliationMemoryTest {
    private val npc = decisionContext().snapshot
    private val first = entity(3, 2.0)
    private val second = entity(4, 1.0)
    private val world = World(first, second)
    @AfterEach fun reset() { BehaviorTargetMemory.clearAll() }

    @Test fun oneIdentityIsConsumedOnceAndFurtherHitsRetainTheFirstTargetAndDeadline() {
        var snapshot = hit(100, first.uuid)
        assertTrue(BehaviorTargetMemory.hasUnhandledDamage(snapshot))
        BehaviorTargetMemory.acquireFromRecentDamage(snapshot, world, BehaviorTargetMemory.Settings(durationTicks = 40))
        assertFalse(BehaviorTargetMemory.hasUnhandledDamage(snapshot))
        for (time in 101L..139L) {
            snapshot = hit(time, second.uuid)
            assertEquals(first.uuid, BehaviorTargetMemory.refresh(snapshot, world)?.uuid)
            assertFalse(BehaviorTargetMemory.hasUnhandledDamage(snapshot))
            BehaviorTargetMemory.acquireFromRecentDamage(snapshot, world)
            assertEquals(first.uuid, BehaviorTargetMemory.targetFor(npc.npcUuid))
        }
        snapshot = hit(140, second.uuid)
        assertNull(BehaviorTargetMemory.refresh(snapshot, world))
        assertFalse(BehaviorTargetMemory.hasUnhandledDamage(snapshot))
        assertTrue(BehaviorTargetMemory.diagnostic(npc.npcUuid)?.contains("deadline") == true)
        snapshot = hit(141, second.uuid)
        BehaviorTargetMemory.acquireFromRecentDamage(snapshot, world)
        assertEquals(second.uuid, BehaviorTargetMemory.targetFor(npc.npcUuid))
    }

    @Test fun leashLossConsumesTheLatestHitAndReassignmentCannotReplayIt() {
        val snapshot = hit(100, first.uuid)
        BehaviorTargetMemory.acquireFromRecentDamage(snapshot, world)
        world.entities[first.uuid] = first.copy(position = NpcPosition(25.0, 64.0, 0.0))
        assertNull(BehaviorTargetMemory.refresh(snapshot.copy(gameTime = 101), world))
        assertTrue(BehaviorTargetMemory.diagnostic(npc.npcUuid)?.contains("leash") == true)
        assertFalse(BehaviorTargetMemory.hasUnhandledDamage(snapshot))
        BehaviorTargetMemory.reset(snapshot)
        assertFalse(BehaviorTargetMemory.hasUnhandledDamage(snapshot))
        assertTrue(BehaviorTargetMemory.hasUnhandledDamage(hit(102, second.uuid)))
    }

    @Test fun unobservableOrForbiddenAttackerIsConsumedWithoutGrantingAnotherTarget() {
        for (target in listOf(first.copy(combat = null), first.copy(combat = facts(allied = true)),
            first.copy(combat = facts(permitted = false)), first.copy(combat = facts(visible = false)),
            first.copy(isPlayer = true), first.copy(combat = facts().copy(summonerUuid = npc.summonerUuid)),
            first.copy(uuid = checkNotNull(npc.summonerUuid)))) {
            BehaviorTargetMemory.remove(npc.npcUuid)
            world.entities.clear(); world.entities[target.uuid] = target
            val snapshot = hit(100, target.uuid)
            BehaviorTargetMemory.acquireFromRecentDamage(snapshot, world)
            assertNull(BehaviorTargetMemory.targetFor(npc.npcUuid), target.toString())
            assertFalse(BehaviorTargetMemory.hasUnhandledDamage(snapshot))
        }
        val missing = hit(101, UUID(0, 90))
        BehaviorTargetMemory.acquireFromRecentDamage(missing, world)
        assertNull(BehaviorTargetMemory.targetFor(npc.npcUuid))
    }

    @Test fun exactSelectionNeverSubstitutesAndNearestUsesStableTiesAndRetention() {
        val area = CombatTargetSelector.Area(npc.position, 24.0)
        assertNull(CombatTargetSelector.exact(npc, world, UUID(0, 99), area))
        assertEquals(first.uuid, CombatTargetSelector.nearest(npc, world, area, setOf(first.typeId), first.uuid)?.uuid)
        world.entities[second.uuid] = second.copy(position = first.position)
        repeat(4) {
            world.reverse = it % 2 == 0
            assertEquals(first.uuid, CombatTargetSelector.nearest(npc, world, area, setOf(first.typeId))?.uuid)
        }
        assertNull(CombatTargetSelector.nearest(npc, world, area, setOf("minecraft:skeleton")))
        world.entities[first.uuid] = first.copy(alive = false)
        assertEquals(second.uuid, CombatTargetSelector.nearest(npc, world, area, setOf(first.typeId), first.uuid)?.uuid)
    }

    @Test fun explicitPlayerPolicyStillRequiresMechanicalPermissionAndRejectsSummoner() {
        val area = CombatTargetSelector.Area(npc.position, 24.0)
        val player = first.copy(isPlayer = true, typeId = "minecraft:player")
        assertFalse(CombatTargetSelector.eligible(npc, player, area))
        assertTrue(CombatTargetSelector.eligible(npc, player, area, allowPlayers = true))
        assertFalse(CombatTargetSelector.eligible(npc, player.copy(combat = facts(permitted = false)), area, allowPlayers = true))
        assertFalse(CombatTargetSelector.eligible(npc, player.copy(uuid = checkNotNull(npc.summonerUuid)), area, allowPlayers = true))
    }

    @Test fun onlyExactBoundedArgumentsAreAccepted() {
        val definition = BehaviorTargetMemory.definition()
        fun valid(json: String) = definition.validateArgs(com.google.gson.JsonParser.parseString(json).asJsonObject) == null
        assertTrue(valid("{}"))
        assertTrue(valid("""{"leash":24,"durationTicks":600,"allowPlayers":false}"""))
        for (json in listOf("""{"leash":33}""", """{"durationTicks":20.5}""", """{"durationTicks":2401}""", """{"allowPlayers":"true"}""", """{"command":"kill"}""")) assertFalse(valid(json), json)
    }

    private fun hit(time: Long, attacker: UUID) = npc.copy(gameTime = time, lastDamageAgeTicks = 0,
        lastDamageSourceEntityUuid = attacker, lastDamageEventId = UUID.randomUUID())
    private fun facts(visible: Boolean = true, permitted: Boolean = true, allied: Boolean = false) = NpcEntityCombatObservation(visible, permitted, allied)
    private fun entity(id: Long, x: Double) = NpcEntityObservation(UUID(0, id), "minecraft:zombie", NpcPosition(x, 64.0, 0.0),
        NpcVector(0.0, 0.0, 0.0), true, false, 1.0, combat = facts())
    private class World(vararg initial: NpcEntityObservation) : NpcWorldView {
        val entities = initial.associateBy { it.uuid }.toMutableMap()
        var reverse = false
        override val dimensionId = "minecraft:overworld"
        override fun observeEntity(uuid: UUID) = entities[uuid]
        override fun queryEntities(query: NpcEntityQuery) = if (reverse) entities.values.reversed() else entities.values.toList()
        override fun observeBlock(position: NpcBlockPosition) = error("unexpected block query")
        override fun observeBlockContainer(position: NpcBlockPosition) = error("unexpected container query")
        override fun raycast(request: NpcRaycastRequest) = error("unexpected raycast")
    }
}
