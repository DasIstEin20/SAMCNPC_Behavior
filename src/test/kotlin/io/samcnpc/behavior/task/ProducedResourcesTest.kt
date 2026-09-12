package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.ContainerTransferDirection
import io.samcnpc.behavior.kernel.inventory.ContainerTransferObservation
import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProducedResourcesTest {
    private val item="minecraft:carrot"
    private fun transfer(before: Int, after: Int, source: Int, recipient: Int) = ContainerTransferObservation(
        NpcBlockPosition(1,2,3),item,if (after>before) ContainerTransferDirection.WITHDRAW else ContainerTransferDirection.DEPOSIT,
        kotlin.math.abs(after-before),before,after,source,recipient,27,"minecraft:chest")
    private fun counts(amount: Int): Map<String,Int> = if (amount == 0) emptyMap() else mapOf(item to amount)

    @Test fun consumedSuppliedSeedsDoNotDiscountTheLaterPhysicalHarvest() {
        val ledger=ProducedResources.initial(emptyMap())
        assertNull(ledger.transfer(transfer(0,4,10,6),ProducedTransfer.SUPPLY,counts(4)))
        assertEquals(0,ledger.available(item))
        assertNull(ledger.consume(item,4,emptyMap()))
        assertNull(ledger.observeLive(counts(10)))
        assertNull(ledger.transfer(transfer(10,0,0,10),ProducedTransfer.DELIVERY,emptyMap()))
        assertEquals(10,ledger.delivered(item))
        val row=ledger.physical.entries.getValue(item)
        assertEquals(4,row.supplied); assertEquals(4,row.consumed); assertEquals(10,row.gathered); assertEquals(10,row.delivered)
        assertTrue(row.valid())
        assertEquals(ledger.entries,ProducedResourcesCodec.read(ProducedResourcesCodec.write(ledger)).entries)
    }
    @Test fun knownRationConsumptionUsesPrivateStockAndNeverCountsAsDelivery() {
        val ledger=ProducedResources.initial(counts(3))
        assertNull(ledger.observeLive(counts(11)))
        assertNull(ledger.consume(item,2,counts(9)))
        assertEquals(1,ledger.entries.getValue(item).stock); assertEquals(8,ledger.available(item))
        assertNull(ledger.transfer(transfer(9,1,0,8),ProducedTransfer.DELIVERY,counts(1)))
        assertEquals(8,ledger.delivered(item)); assertEquals(1,ledger.physical.retained()[item])
    }
    @Test fun unexplainedLossConservativelyReducesOutputBeforePrivateStock() {
        val ledger=ProducedResources.initial(counts(3))
        assertNull(ledger.observeLive(counts(11))); assertNull(ledger.observeLive(counts(9)))
        assertEquals(6,ledger.available(item)); assertEquals(3,ledger.entries.getValue(item).stock)
        assertEquals(2,ledger.physical.entries.getValue(item).lost)
        assertEquals(0,ledger.delivered(item))
    }
    @Test fun explicitAuthorizedFoodSourceIsCargoButAuxiliaryStockIsNot() {
        val ledger=ProducedResources.initial(counts(2))
        assertNull(ledger.transfer(transfer(2,7,20,15),ProducedTransfer.SUPPLY,counts(7)))
        assertNull(ledger.transfer(transfer(7,19,15,3),ProducedTransfer.OUTPUT_SOURCE,counts(19)))
        assertEquals(12,ledger.available(item)); assertEquals(7,ledger.entries.getValue(item).stock)
        assertNull(ledger.transfer(transfer(19,12,0,7),ProducedTransfer.DELIVERY,counts(12)))
        assertEquals(7,ledger.delivered(item)); assertEquals(5,ledger.available(item))
        assertEquals(0,ledger.physical.entries.getValue(item).gathered)
        assertEquals(ledger.entries,ProducedResourcesCodec.read(ProducedResourcesCodec.write(ledger)).entries)
    }
    @Test fun auxiliaryUnloadReportsPhysicalTransferWithoutFakingGoalProgress() {
        val ledger=ProducedResources.initial(counts(4)); assertNull(ledger.observeLive(counts(7)))
        assertNull(ledger.transfer(transfer(7,2,0,5),ProducedTransfer.AUXILIARY_UNLOAD,counts(2)))
        assertEquals(2,ledger.available(item)); assertEquals(0,ledger.delivered(item))
        assertEquals(5,ledger.physical.entries.getValue(item).delivered)
    }
    @Test fun invalidCreditAndMismatchFailClosedWithoutRewritingThePhysicalCheckpoint() {
        val ledger=ProducedResources.initial(counts(4))
        assertNotNull(ledger.transfer(transfer(4,2,0,2),ProducedTransfer.DELIVERY,counts(2)))
        assertTrue(ledger.physical.uncertain); assertEquals(counts(4),ledger.physical.retained())
        assertEquals(0,ledger.delivered(item))
        val consumption=ProducedResources.initial(counts(4))
        assertNotNull(consumption.consume(item,3,counts(2)))
        assertEquals(counts(4),consumption.physical.retained())
    }
    @Test fun restoredOriginKeepsItsCheckpointAndRejectsForgedCredit() {
        val ledger=ProducedResources.initial(counts(3)); assertNull(ledger.observeLive(counts(8)))
        val restored=ProducedResourcesCodec.read(ProducedResourcesCodec.write(ledger))
        assertNull(restored.reconcileLoad(null,counts(8)))
        assertNull(restored.observeLive(counts(9))); assertEquals(6,restored.available(item))
        val mismatched=ProducedResourcesCodec.read(ProducedResourcesCodec.write(ledger))
        assertNotNull(mismatched.reconcileLoad(null,counts(9))); assertTrue(mismatched.physical.uncertain)
        val forged=ProducedResourcesCodec.write(ledger)
        forged.getList("origins",Tag.TAG_COMPOUND.toInt()).getCompound(0).putInt("deliveredOutput",1)
        assertFailsWith<IllegalArgumentException> { ProducedResourcesCodec.read(forged) }
        val copy=ledger.entries.getValue(item); copy.output=99
        assertEquals(5,ledger.available(item))
    }
}
