package io.samcnpc.behavior.runtime

import com.google.gson.JsonObject
import io.samcnpc.behavior.registry.BehaviorDefinitions
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcEntityObservation
import io.samcnpc.core.api.NpcEntityQuery
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcRaycastRequest
import io.samcnpc.core.api.NpcVector
import io.samcnpc.core.api.NpcWorldView
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BehaviorConditionPurityTest {
    @Test
    fun repeatedTargetConditionsDoNotPruneMemoryOrReadTheWorld() {
        val context = decisionContext()
        val npc = context.snapshot.npcUuid
        val target = NpcEntityObservation(UUID(0, 3), "minecraft:zombie", NpcPosition(2.0, 64.0, 0.0),
            NpcVector(0.0, 0.0, 0.0), false, false, 0.0)
        val conditions = listOf("samcnpc:has_attack_target", "samcnpc:target_alive").map {
            BehaviorDefinitions.conditions.getValue(it).compile(JsonObject())
        }
        var observations = 0
        var observed = target.copy(alive = true, healthFraction = 1.0, combat = io.samcnpc.core.api.NpcEntityCombatObservation(true, true, false))
        val world = object : NpcWorldView {
            override val dimensionId = "minecraft:overworld"
            override fun observeEntity(uuid: UUID): NpcEntityObservation { observations++; assertEquals(target.uuid, uuid); return observed }
            override fun queryEntities(query: NpcEntityQuery) = error("unexpected query")
            override fun observeBlock(position: NpcBlockPosition) = error("unexpected block observation")
            override fun observeBlockContainer(position: NpcBlockPosition) = error("unexpected container observation")
            override fun raycast(request: NpcRaycastRequest) = error("unexpected raycast")
        }
        val damageSnapshot = context.snapshot.copy(lastDamageSourceEntityUuid = target.uuid,
            lastDamageAgeTicks = 0, lastDamageEventId = UUID.randomUUID())
        BehaviorTargetMemory.acquireFromRecentDamage(damageSnapshot, world)
        assertEquals(target.uuid, BehaviorTargetMemory.targetFor(npc))
        observed = observed.copy(alive = false, healthFraction = 0.0)
        observations = 0
        try {
            repeat(20) {
                for (condition in conditions) assertFalse(condition.evaluate(context.copy(attackTarget = target)))
                assertEquals(target.uuid, BehaviorTargetMemory.targetFor(npc))
            }
            assertEquals(0, observations)
            assertNull(BehaviorTargetMemory.refresh(damageSnapshot, world))
            assertEquals(1, observations)
            assertNull(BehaviorTargetMemory.targetFor(npc))
        } finally {
            BehaviorTargetMemory.remove(npc)
        }
    }

    @Test
    fun oneDecisionContextKeepsItsFactsEvenWhenTheNextObservationChanges() {
        val target = NpcEntityObservation(UUID(0, 3), "minecraft:zombie", NpcPosition(2.0, 64.0, 0.0),
            NpcVector(0.0, 0.0, 0.0), true, false, 1.0)
        val context = decisionContext().copy(attackTarget = target)
        val condition = BehaviorDefinitions.conditions.getValue("samcnpc:target_alive").compile(JsonObject())
        assertTrue(condition.evaluate(context))
        assertFalse(condition.evaluate(context.copy(attackTarget = target.copy(alive = false))))
        assertTrue(condition.evaluate(context))
    }
}
