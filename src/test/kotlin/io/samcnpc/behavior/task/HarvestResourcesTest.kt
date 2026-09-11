package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class HarvestResourcesTest {
    private val oak = "minecraft:oak_log"
    private val wood = WoodSelection(listOf("samcnpc:oak"))

    @Test fun borrowingAndReturningWoodDoesNotProduceNetHarvest() {
        val ledger = HarvestResources(emptyMap())
        assertNull(ledger.observeStep(mapOf(oak to 4), mapOf(oak to 10), mapOf(oak to 6), false))
        assertEquals(0, ledger.potentialDelivery(wood, 4))
        assertNull(ledger.observeStep(emptyMap(), mapOf(oak to 6), mapOf(oak to 10), false))
        assertEquals(0, ledger.delivered(wood))
        assertEquals(4, ledger.entries[oak]?.supplied)
        assertEquals(4, ledger.entries[oak]?.delivered)
    }

    @Test fun actualPickupPlacementRecoveryDeliveryAndLossConserveEveryItem() {
        val ledger = HarvestResources(mapOf(oak to 2))
        assertNull(ledger.observeLive(mapOf(oak to 7)))
        assertNull(ledger.observeStep(mapOf(oak to 6), emptyMap(), emptyMap(), true))
        assertNull(ledger.observeLive(mapOf(oak to 7)))
        assertNull(ledger.observeStep(mapOf(oak to 3), emptyMap(), mapOf(oak to 4), false))
        assertNull(ledger.observeLive(mapOf(oak to 2)))
        assertEquals(HarvestResource(initial = 2, gathered = 6, consumed = 1, delivered = 4, lost = 1, retained = 2), ledger.entries[oak])
        assertTrue(ledger.entries.values.all { it.valid() })
        assertEquals(4, ledger.delivered(wood))
    }

    @Test fun containerOnlyChangeIsNotSilentlyAcceptedAsANoop() {
        val ledger = HarvestResources(mapOf(oak to 5))
        val before = ledger.entries
        assertNotNull(ledger.observeStep(mapOf(oak to 5), mapOf(oak to 10), mapOf(oak to 11), false))
        assertTrue(ledger.uncertain)
        assertEquals(before, ledger.entries)
    }

    @Test fun malformedOrExcessiveObservationsNeverPartiallyRewriteTheLedger() {
        val ledger = HarvestResources(mapOf(oak to 5))
        val before = ledger.entries
        assertNotNull(ledger.observeLive((0..64).associate { "test:item_$it" to 1 }))
        assertEquals(before, ledger.entries)
        assertNotNull(ledger.observeLive(mapOf(oak to -1)))
        assertEquals(before, ledger.entries)
        assertFailsWith<IllegalArgumentException> { HarvestResources.restore(mapOf(oak to HarvestResource(initial = 5, retained = 8)), false) }
    }

    @Test fun anOldLoadedInventoryCannotBeHiddenByLivePickupOrDrop() {
        val empty = NpcItemStackSnapshot.EMPTY
        val old = NpcItemStackSnapshot(oak, 8, 64, 0, 0)
        val equipment = NpcEquipmentSnapshot(old, empty, empty, empty, empty, empty)
        val loaded = NpcInventoryLoadSnapshot(UUID.randomUUID(), listOf(old) + List(35) { empty }, equipment)
        val ledger = HarvestResources(mapOf(oak to 5))
        assertNotNull(ledger.reconcileLoad(loaded, mapOf(oak to 5)))
        assertTrue(ledger.uncertain)
        assertEquals(5, ledger.entries[oak]?.retained)
    }

    @Test fun newSpeciesDoesNotBroadenAnExistingSelectionOrCountUnselectedDelivery() {
        val darkOak = WoodSelection(listOf("samcnpc:dark_oak"))
        val oldMix = WoodSelection(listOf("samcnpc:oak_and_birch"))
        assertNull(darkOak.validationProblem())
        assertTrue(darkOak.matches("minecraft:stripped_dark_oak_wood"))
        assertFalse(darkOak.matches(oak))
        assertFalse(oldMix.matches("minecraft:dark_oak_log"))
        assertTrue(oldMix.matches(oak) && oldMix.matches("minecraft:birch_log"))
        assertNotNull(WoodSelection(listOf("samcnpc:spruce")).validationProblem())
        val selected = "minecraft:dark_oak_log"
        val ledger = HarvestResources(emptyMap())
        assertNull(ledger.observeLive(mapOf(selected to 5, oak to 3)))
        assertNull(ledger.observeStep(emptyMap(), emptyMap(), mapOf(selected to 5, oak to 3), false))
        assertEquals(5, ledger.delivered(darkOak))
        assertEquals(3, ledger.delivered(oldMix))
        assertTrue(ledger.entries.values.all { it.valid() })
    }

    @Test fun workGeometryAndWoodSelectionRemainBoundedAndImmutable() {
        val min = NpcBlockPosition(0, 60, 0); val max = NpcBlockPosition(9, 80, 9)
        val exclusions = mutableListOf(WorkBox(NpcBlockPosition(2, 60, 2), NpcBlockPosition(3, 80, 3)))
        val area = WorkArea(WorkBox(min, max), exclusions)
        exclusions.clear()
        assertFalse(area.contains(NpcBlockPosition(2, 65, 2)))
        assertTrue(area.contains(min)); assertFalse(area.contains(NpcBlockPosition(-1, 60, 0)))
        assertEquals(100, area.columns)
        assertEquals(NpcBlockPosition(9, 60, 9), area.column(99))
        assertNotNull(WorkArea(WorkBox(min, max.copy(x = Int.MAX_VALUE))).validationProblem())
        assertNotNull(WoodSelection(listOf("arbitrary:remote_url")).validationProblem())
        assertTrue(wood.matches("minecraft:stripped_oak_log")); assertFalse(wood.matches("minecraft:birch_log"))
        val exact = WoodSelection(listOf("minecraft:oak_log"))
        assertFalse(exact.matches("minecraft:stripped_oak_log"))
    }
}
