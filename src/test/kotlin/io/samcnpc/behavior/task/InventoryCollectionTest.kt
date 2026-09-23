package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatPolicyWorld
import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class InventoryCollectionTest {
    private val source = NpcBlockPosition(-2, 64, 0)
    private val start = NpcPosition(0.5, 64.0, 0.5)
    private fun definition(maxItems: Int = 2304) = InventoryTaskDefinition("minecraft:overworld", CollectContainer(source, maxItems), start)
    private fun slot(index: Int, id: String?, count: Int) = NpcBlockContainerSlotObservation(index,
        if (id == null) NpcItemStackSnapshot.EMPTY else NpcItemStackSnapshot(id, count, 64, 0, 0),
        if (id == null) NpcItemKnowledge.EMPTY else NpcItemKnowledge(id, setOf(NpcItemRole.OTHER)))
    private fun observed(items: Map<String, Int>) = NpcBlockContainerObservation(source, 27,
        List(27) { index -> items.entries.elementAtOrNull(index)?.let { slot(index, it.key, it.value) } ?: slot(index, null, 0) })
    private fun captured(body: Body, d: InventoryTaskDefinition = definition()) =
        assertIs<InventoryCaptureResult.Captured>(InventoryTaskCapture.capture(body, d, 0)).state

    @Test fun capturesAdditionalWithdrawalsAndCannotExpandWhenSourceOrCarriedStockChanges() {
        val original = linkedMapOf("minecraft:dirt" to 64, "minecraft:iron_axe" to 1, "minecraft:iron_helmet" to 1)
        val body = Body(observed(original))
        val state = captured(body)
        assertEquals(original, state.goals)
        assertEquals(64, state.resources.retained()["minecraft:dirt"])
        assertFalse(state.satisfied(definition().work))
        body.contents = observed(mapOf("minecraft:dirt" to 1, "minecraft:diamond" to 64))
        body.carried = 32
        assertEquals(original, state.goals)
        assertFalse(state.satisfied(definition().work))
        assertFailsWith<UnsupportedOperationException> { (state.goals as MutableMap).clear() }
        assertEquals(listOf("minecraft:dirt", "minecraft:iron_axe", "minecraft:iron_helmet"), state.capturedItemIds)
    }

    @Test fun unknownIncompleteDuplicateAndOversizedSourcesRejectBeforeAnyAction() {
        val complete = observed(mapOf("minecraft:dirt" to 64))
        val invalid = listOf(null, complete.copy(slots = complete.slots.dropLast(1)),
            complete.copy(slots = complete.slots.dropLast(1) + complete.slots.first()),
            complete.copy(containerSize = 65), complete.copy(position = source.copy(x = 3)),
            complete.copy(slots = complete.slots.map { if (it.slot == 0) slot(0, "minecraft:dirt", 0) else it }),
            complete.copy(slots = complete.slots.map { if (it.slot == 0) slot(0, "minecraft:dirt", -1) else it }),
            complete.copy(slots = complete.slots.map { if (it.slot == 0) it.copy(stack = NpcItemStackSnapshot.EMPTY.copy(count = 1)) else it }))
        for (contents in invalid) assertIs<InventoryCaptureResult.Rejected>(InventoryTaskCapture.capture(Body(contents), definition(), 0))
        assertIs<InventoryCaptureResult.Rejected>(InventoryTaskCapture.capture(Body(complete), definition(63), 0))
        val tooMany = observed((0..16).associate { "fixture:item_$it" to 1 })
        assertEquals("COLLECTION_ITEM_LIMIT", assertIs<InventoryCaptureResult.Rejected>(
            InventoryTaskCapture.capture(Body(tooMany), definition(), 0)).code)
        val combined = observed(mapOf("minecraft:dirt" to 64, "minecraft:stone" to 64))
        assertIs<InventoryCaptureResult.Rejected>(InventoryTaskCapture.capture(Body(combined), definition(127), 0))
        assertNotNull(definition(0).validationProblem())
        assertNotNull(definition(2305).validationProblem())
        assertNotNull(definition().copy(version = 1).validationProblem())
    }

    @Test fun anObservedEmptySourceIsARealEmptyQuota() {
        val d = definition()
        val state = captured(Body(observed(emptyMap())), d)
        assertTrue(state.goals.isEmpty())
        assertTrue(state.satisfied(d.work))
        assertEquals(setOf(source), state.checkpoints.keys)
    }

    @Test fun persistenceRetainsTheExactQuotaSourceAndLimitsWithoutRecapture() {
        val body = Body(observed(mapOf("minecraft:iron_axe" to 1, "minecraft:dirt" to 32)))
        val d = definition(33)
        val record = TaskRecord.start(body.npcUuid, d, emptyList())
        record.primary.inventory = captured(body, d)
        val saved = TaskCodec.write(record)
        body.contents = null
        val restored = TaskCodec.read(saved)
        val state = checkNotNull(restored.primary.inventory)
        assertEquals(checkNotNull(record.primary.inventory).goals, state.goals)
        assertEquals(setOf(source), state.checkpoints.keys)
        assertEquals(2, restored.primary.definition.version)
        val encoded = InventoryStateCodec.write(state)
        val oversized = encoded.copy()
        oversized.put("goals", LumberjackTaskCodec.counts(mapOf("minecraft:dirt" to 34)))
        assertFailsWith<IllegalArgumentException> { InventoryStateCodec.read(oversized, d) }
        val missingSource = encoded.copy(); missingSource.put("checkpoints", net.minecraft.nbt.ListTag())
        assertFailsWith<IllegalArgumentException> { InventoryStateCodec.read(missingSource, d) }
        assertEquals(InventoryWorkCodec.write(d.work), InventoryWorkCodec.write(InventoryWorkCodec.read(InventoryWorkCodec.write(d.work))))
    }

    private inner class Body(var contents: NpcBlockContainerObservation?) : TestNpcFacade() {
        var carried = 64
        private val world = object : NpcWorldView by CombatPolicyWorld() {
            override fun observeBlock(position: NpcBlockPosition) = if (position == source)
                NpcBlockObservation(source, "minecraft:chest", false, true, true) else null
            override fun observeBlockContainer(position: NpcBlockPosition) = contents
        }
        override fun snapshot() = decisionContext().snapshot
        override fun inventoryContents() = listOf(NpcInventoryEntry(1, NpcItemStackSnapshot("minecraft:dirt", carried, 64, 0, 0)))
        override fun equipmentContents() = NpcEquipmentSnapshot(NpcItemStackSnapshot.EMPTY, NpcItemStackSnapshot.EMPTY,
            NpcItemStackSnapshot.EMPTY, NpcItemStackSnapshot.EMPTY, NpcItemStackSnapshot.EMPTY, NpcItemStackSnapshot.EMPTY)
        override fun worldView() = world
    }
}
