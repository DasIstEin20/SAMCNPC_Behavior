package io.samcnpc.behavior.inventory

import com.google.gson.JsonParser
import io.samcnpc.behavior.registry.BehaviorDefinitions
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class LocalInventoryComponentsTest {
    private val empty = NpcItemStackSnapshot.EMPTY
    private fun condition(id: String, text: String): io.samcnpc.behavior.model.ConditionHandler {
        val definition = BehaviorDefinitions.conditions.getValue("samcnpc:$id")
        val args = JsonParser.parseString(text).asJsonObject
        assertNull(definition.validateArgs(args)); return definition.compile(args)
    }
    @Test fun missingFactsAreUnknownEvenForEqualityZeroAndLessThan() {
        val context = decisionContext()
        for (op in listOf("eq", "lte", "gte")) {
            assertFalse(condition("inventory_count", """{"query":"minecraft:coal","operator":"$op","count":0}""").evaluate(context))
        }
        assertFalse(condition("task_attempts_remaining", """{"operator":"eq","count":0}""").evaluate(context))
        for (op in listOf("eq", "lte", "gte")) {
            assertFalse(condition("container_count", """{"endpoint":"SOURCE","index":0,"query":"minecraft:coal","operator":"$op","count":0}""").evaluate(context))
            assertFalse(condition("container_free_slots", """{"endpoint":"SOURCE","index":0,"operator":"$op","count":0}""").evaluate(context))
        }
        assertFalse(condition("container_observed", """{"endpoint":"SOURCE","index":0}""").evaluate(context))
    }
    @Test fun compiledInventoryConditionsArePureAndRemainDetachedFromNextCapture() {
        val coal = NpcItemStackSnapshot("minecraft:coal", 2, 64, 0, 0)
        val entries = (0..35).map { NpcInventoryEntry(it, if (it == 0) coal else empty) }.toMutableList()
        val facts = InventoryFacts(entries, NpcEquipmentSnapshot(coal, empty, empty, empty, empty, empty), NpcEquipmentKnowledge.EMPTY)
        val condition = condition("inventory_count", """{"query":"minecraft:coal","operator":"gte","count":2}""")
        val context = decisionContext().copy(inventoryFacts = facts)
        entries[0] = NpcInventoryEntry(0, empty)
        repeat(30) { assertTrue(condition.evaluate(context)) }
        assertFalse(condition.evaluate(context.copy(inventoryFacts = InventoryFacts(entries, NpcEquipmentSnapshot(empty, empty, empty, empty, empty, empty), NpcEquipmentKnowledge.EMPTY))))
    }
    @Test fun equipmentDurabilityAndTaskFactsHaveExplicitSemantics() {
        val axe = NpcItemStackSnapshot("minecraft:iron_axe", 1, 1, 225, 250)
        val knowledge = NpcItemKnowledge("minecraft:iron_axe", setOf(NpcItemRole.TOOL), NpcToolKind.AXE)
        val entries = (0..35).map { NpcInventoryEntry(it, if (it == 0) axe else empty, if (it == 0) knowledge else NpcItemKnowledge.EMPTY) }
        val facts = InventoryFacts(entries, NpcEquipmentSnapshot(axe, empty, empty, empty, empty, empty), NpcEquipmentKnowledge.EMPTY.copy(mainHand = knowledge))
        val context = decisionContext().copy(inventoryFacts = facts, localTaskFacts = LocalTaskFacts("RUNNING", 2, "NO_PROGRESS"))
        assertTrue(condition("equipment_matches", """{"query":"@axe","destination":"MAIN_HAND","minimumDurability":0.1}""").evaluate(context))
        assertFalse(condition("equipment_matches", """{"query":"@axe","destination":"MAIN_HAND","minimumDurability":0.11}""").evaluate(context))
        assertTrue(condition("durability_fraction", """{"destination":"MAIN_HAND","operator":"eq","value":0.1}""").evaluate(context))
        assertTrue(condition("task_status", """{"status":"RUNNING"}""").evaluate(context))
        assertTrue(condition("last_task_failure", """{"reason":"NO_PROGRESS"}""").evaluate(context))
        assertTrue(condition("task_attempts_remaining", """{"operator":"gte","count":2}""").evaluate(context))
        assertTrue(condition("at_position", """{"x":0,"y":64,"z":0,"radius":0}""").evaluate(context))
    }
}
