package io.samcnpc.behavior.inventory

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ItemQueryTest {
    private fun item(id: String, count: Int = 1, damage: Int = 0, maxDamage: Int = 0) = NpcItemStackSnapshot(id, count, if (maxDamage > 0) 1 else 64, damage, maxDamage)
    private fun facts(id: String, kind: NpcToolKind? = null) = NpcItemKnowledge(id, if (kind == null) setOf(NpcItemRole.OTHER) else setOf(NpcItemRole.TOOL), toolKind = kind)
    @Test fun exactCoalIsNotCharcoalAndAlternativesCountEachPhysicalStackOnce() {
        val coal = ItemQuery.parse("minecraft:coal"); val charcoal = item("minecraft:charcoal", 64)
        assertFalse(coal.matches(charcoal, facts("minecraft:charcoal")))
        val any = ItemQuery.parse("minecraft:coal|minecraft:charcoal")
        assertTrue(any.matches(charcoal, facts("minecraft:charcoal")))
        val entries = (0..35).map { NpcInventoryEntry(it, if (it == 0) charcoal else NpcItemStackSnapshot.EMPTY, if (it == 0) facts("minecraft:charcoal") else NpcItemKnowledge.EMPTY) }
        val e = NpcItemStackSnapshot.EMPTY
        val equipment = NpcEquipmentSnapshot(charcoal, item("minecraft:coal", 2), e, e, e, e, item("minecraft:coal", 3), e)
        val knowledge = NpcEquipmentKnowledge.EMPTY.copy(mainHand = facts("minecraft:charcoal"), offHand = facts("minecraft:coal"), ammunition = facts("minecraft:coal"))
        val inventory = InventoryFacts(entries, equipment, knowledge)
        assertEquals(5, inventory.count(coal)); assertEquals(69, inventory.count(any)); assertEquals(35, inventory.freeSlots)
    }
    @Test fun rolesUseAuthoritativeKnowledgeRatherThanItemSpelling() {
        val axe = ItemQuery.parse("@axe")
        assertFalse(axe.matches(item("minecraft:iron_axe"), NpcItemKnowledge.EMPTY))
        assertFalse(axe.matches(item("test:axe_named_food"), facts("test:axe_named_food")))
        assertTrue(axe.matches(item("test:unusual_tool"), facts("test:unusual_tool", NpcToolKind.AXE)))
        assertFalse(axe.matches(item("test:unusual_tool"), facts("minecraft:iron_axe", NpcToolKind.AXE)))
    }
    @Test fun queriesAreImmutableBoundedAndRoundTrip() {
        for (text in listOf("minecraft:coal", "@pickaxe", "minecraft:coal|@food", "@axe|@pickaxe|@shovel|@hoe")) {
            val query = ItemQuery.parse(text); assertEquals(text, query.encode()); assertEquals(query, ItemQuery.parse(query.encode()))
        }
        for (text in listOf("", "coal", "@invented", "@AXE", "minecraft:coal|minecraft:coal", "minecraft:coal||minecraft:charcoal", " minecraft:coal", (1..9).joinToString("|") { "test:p$it" }, "a:" + "b".repeat(127))) {
            assertFailsWith<IllegalArgumentException>(text) { ItemQuery.parse(text) }
        }
        val alternatives = mutableListOf<ItemQuery>(ItemQuery.Exact("minecraft:coal"), ItemQuery.Role(ItemQueryRole.FOOD))
        val query = ItemQuery.AnyOf(alternatives); alternatives.clear(); assertEquals(2, query.alternatives.size)
        assertFailsWith<UnsupportedOperationException> { (query.alternatives as MutableList).clear() }
    }
    @Test fun deterministicChoiceAvoidsWornToolsRespectsDestinationAndStableSlotOrder() {
        val id = "minecraft:iron_axe"; val k = facts(id, NpcToolKind.AXE)
        val worn = NpcInventoryEntry(0, item(id, damage = 245, maxDamage = 250), k)
        val backup = NpcInventoryEntry(12, item(id, maxDamage = 250), k)
        val other = backup.copy(slot = 20)
        val query = ItemQuery.parse("@axe")
        assertEquals(backup, EquipmentPreparation.choose(listOf(other, worn, backup), query, NpcEquipmentDestination.MAIN_HAND, 0.1, 0))
        assertNull(EquipmentPreparation.choose(listOf(worn), query, NpcEquipmentDestination.MAIN_HAND, 0.1, 0))
        assertNull(EquipmentPreparation.choose(listOf(backup), query, NpcEquipmentDestination.HEAD, 0.0, 0))
        assertFalse(InventoryFacts.matches(query, item(id, damage = 250, maxDamage = 250), k, 0.0))
    }
    @Test fun semanticFuzzPreservesUnionAndExactIdentity() {
        val random = java.util.Random(20260927)
        repeat(300) {
            val amount = random.nextInt(64) + 1
            val id = if (random.nextBoolean()) "minecraft:coal" else "minecraft:charcoal"
            val stack = item(id, amount); val known = facts(id)
            assertEquals(id == "minecraft:coal", ItemQuery.parse("minecraft:coal").matches(stack, known))
            assertTrue(ItemQuery.parse("minecraft:coal|minecraft:charcoal").matches(stack, known))
        }
    }
    @Test fun exportedQueryGrammarRequiresSeparatorsAndRejectsTrailingOrLeadingPipes() {
        val pattern=Regex(ItemQuery.PATTERN)
        for (role in ItemQueryRole.entries) assertTrue(pattern.matches(ItemQuery.Role(role).encode()))
        for (size in 1..8) assertTrue(pattern.matches((1..size).joinToString("|") { "test:item_$it" }))
        for (invalid in listOf("|@axe","@axe|","@axe||@food","@axe@food","a:bc:d","@invented",(1..9).joinToString("|") { "test:item_$it" })) assertFalse(pattern.matches(invalid),invalid)
    }
}
