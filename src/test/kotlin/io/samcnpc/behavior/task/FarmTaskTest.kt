package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class FarmTaskTest {
    private val cell=NpcBlockPosition(4,64,0)
    private val recipient=NpcBlockPosition(0,64,3)
    private fun definition(crop: FarmCrop=FarmCrop.WHEAT,mode: FarmMode=FarmMode.REPLANT)=FarmTaskDefinition("minecraft:overworld",
        FarmWorkOrder(WorkArea(WorkBox(cell,cell)),crop,mode,keepSeeds=if (mode == FarmMode.HARVEST) 0 else 1,growthWaitTicks=100,growthCheckTicks=20),
        ContainerChoices(listOf(recipient)),1,NpcPosition(0.5,64.0,0.5))
    @Test fun fieldModesRejectUnsupportedGeometryAndUnauthorizedWorkBeforeExecution() {
        val d=definition()
        assertNull(d.validationProblem()); assertEquals(listOf(cell),d.work.cells)
        assertNotNull(d.copy(work=d.work.copy(area=WorkArea(WorkBox(cell,cell.copy(y=65))))).validationProblem())
        assertNotNull(d.copy(work=d.work.copy(cycles=9)).validationProblem())
        assertNotNull(d.copy(work=d.work.copy(mode=FarmMode.HARVEST)).validationProblem())
        assertNotNull(d.copy(work=d.work.copy(area=WorkArea(WorkBox(cell,cell),listOf(WorkBox(cell,cell))))).validationProblem())
        for (crop in FarmCrop.entries) for (mode in FarmMode.entries) {
            val value=definition(crop,mode)
            assertEquals(value,TaskCodec.readDefinition(TaskCodec.writeDefinition(value)))
        }
    }
    @Test fun cropCollectionNeedsConfirmedHarvestAndIncludesFractionalFarmlandTopOnlyNearby() {
        val d=definition(); val s=FarmTaskState(ProducedResources.initial(emptyMap()),d)
        val drop=NpcPosition(4.5,63.9375,0.5)
        assertFalse(FarmAccounting.allowsPickup(d,s,drop))
        s.harvested[cell]=1
        assertTrue(FarmAccounting.allowsPickup(d,s,drop))
        assertTrue(FarmAccounting.allowsPickup(d,s,drop.copy(x=5.7)))
        assertFalse(FarmAccounting.allowsPickup(d,s,drop.copy(x=6.1)))
        assertFalse(FarmAccounting.allowsPickup(d,s,drop.copy(y=62.999)))
    }
    @Test fun irrigationLootNeedsItsHarvestedNeighborAndCannotCreditDeeperOrDistantDrops() {
        val d=definition(); val s=FarmTaskState(ProducedResources.initial(emptyMap()),d)
        val waterDrop=NpcPosition(5.875,63.53326458258492,0.125)
        assertFalse(FarmAccounting.allowsPickup(d,s,waterDrop))
        s.harvested[cell]=1
        assertTrue(FarmAccounting.allowsPickup(d,s,waterDrop))
        assertTrue(FarmAccounting.allowsPickup(d,s,waterDrop.copy(y=63.0)))
        assertFalse(FarmAccounting.allowsPickup(d,s,waterDrop.copy(y=62.999)))
        assertFalse(FarmAccounting.allowsPickup(d,s,waterDrop.copy(x=6.001)))
        assertFalse(FarmAccounting.allowsPickup(d,s,waterDrop.copy(z=2.001)))
        s.harvested.clear(); s.harvested[cell.copy(x=6)]=1
        assertFalse(FarmAccounting.allowsPickup(d,s,waterDrop.copy(x=4.49)))
    }
    @Test fun growthClockIsFiniteAcrossInterruptionsAndPauseDoesNotSpendIt() {
        val d=definition(mode=FarmMode.CULTIVATE); val s=FarmTaskState(ProducedResources.initial(emptyMap()),d)
        val record=TaskRecord.start(UUID(0,1),d,emptyList()); record.primary.farming=s
        s.phase=FarmPhase.WAIT_GROWTH; s.nextGrowthCheck=20
        record.advanceTime(7)
        assertEquals(93,s.growthRemaining); assertEquals(13,s.nextGrowthCheck)
        assertNull(record.interrupt(NavigateTaskDefinition(d.dimensionId,d.anchor)))
        record.advanceTime(8); assertEquals(85,s.growthRemaining)
        record.pause(); record.advanceTime(50); assertEquals(85,s.growthRemaining)
        record.resume(); record.advanceTime(90)
        assertEquals(0,s.growthRemaining); assertEquals(0,s.nextGrowthCheck); assertEquals(d.budget.ticks-105,record.primary.remainingTicks)
    }
    @Test fun cropHarvestPauseRoundTripsOriginalIntentAndRejectsForgedFutureState() {
        val d=definition(); val s=FarmTaskState(ProducedResources.initial(mapOf(d.work.crop.seedId to 2)),d)
        val record=TaskRecord.start(UUID(0,1),d,emptyList()); record.primary.farming=s
        s.phase=FarmPhase.HARVEST; s.target=cell; s.cursor=1
        record.advanceTime(122); record.pause()
        val tag=TaskCodec.write(record); val restored=TaskCodec.read(tag)
        assertEquals(tag,TaskCodec.write(restored)); assertTrue(checkNotNull(restored.primary.farming).reconcileWorld)
        assertEquals(d.budget.ticks-122,restored.primary.remainingTicks)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(tag,4) }
        val wrong=tag.copy(); wrong.getList("frames",10).getCompound(0).getCompound("farming").putInt("cycle",20)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(wrong) }
    }
    private fun deliveredCarrots(): Pair<FarmTaskDefinition,FarmTaskState> {
        val d=definition(FarmCrop.CARROT).copy(quantity=3)
        val item=d.work.crop.itemId
        val s=FarmTaskState(ProducedResources.initial(mapOf(item to 2)),d)
        assertNull(s.resources.pickup(item,3,mapOf(item to 5))); s.pickups[item]=3
        s.harvested[cell]=1; s.cycleDone.add(cell)
        assertNull(s.resources.consume(item,1,mapOf(item to 4),ProducedGain.STOCK)); s.planted[cell]=1; s.replanted[cell]=1
        val transfer=ContainerTransferObservation(recipient,item,ContainerTransferDirection.DEPOSIT,3,4,1,0,3,27,"minecraft:chest")
        assertNull(s.resources.transfer(transfer,ProducedTransfer.DELIVERY,mapOf(item to 1),ProducedGain.STOCK))
        s.checkpoints[recipient]=ContainerCheckpoint("minecraft:chest",27); s.deliveries[recipient]=mapOf(item to 3)
        s.phase=FarmPhase.RETURN; s.cycle=1; s.exhausted=true; s.cursor=1
        return d to s
    }
    @Test fun realSeedConsumptionAndReplantAreRequiredAlongsideActualCropDelivery() {
        val (d,s)=deliveredCarrots(); assertTrue(s.goal(d)); assertEquals(0,s.deliverable(d))
        val record=TaskRecord.start(UUID(0,1),d,emptyList()); record.primary.farming=s
        record.finish(TaskStatus.COMPLETED,TaskReason.FARM_FINISHED,"crop delivered and resown")
        assertEquals(record.report(),TaskCodec.read(TaskCodec.write(record)).report())
        s.replanted.clear(); assertFalse(s.goal(d))
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(record)) }
    }
    @Test fun plantedCellsCannotExceedPhysicalSeedConsumptionOrEscapeField() {
        val d=definition(); val s=FarmTaskState(ProducedResources.initial(mapOf(d.work.crop.seedId to 3)),d)
        s.planted[cell]=1
        assertFailsWith<IllegalArgumentException> { FarmStateCodec.read(FarmStateCodec.write(s),d) }
        s.planted.clear(); s.harvested[cell.copy(x=100)]=1
        assertFailsWith<IllegalArgumentException> { FarmStateCodec.read(FarmStateCodec.write(s),d) }
    }
    @Test fun physicalCropSeedStepsDeferChangesWhileTimeExtensionRemainsAvailable() {
        val d=definition(); val s=FarmTaskState(ProducedResources.initial(emptyMap()),d)
        val record=TaskRecord.start(UUID(0,1),d,emptyList()); record.primary.farming=s
        for (phase in listOf(FarmPhase.HARVEST,FarmPhase.COLLECT,FarmPhase.SOIL,FarmPhase.PLANT,FarmPhase.SUPPLY)) {
            s.phase=phase; s.target=cell
            assertFalse(TaskAmendmentPreparation.boundary(record,TaskChange.Quantity(4,QuantityChangeMode.TOTAL)))
            assertTrue(TaskAmendmentPreparation.boundary(record,TaskChange.ExtendTime(100)))
        }
        s.target=null; s.phase=FarmPhase.SELECT
        assertTrue(TaskAmendmentPreparation.boundary(record,TaskChange.Quantity(4,QuantityChangeMode.TOTAL)))
        val next=TaskChanges.proposed(d,TaskChange.Quantity(4,QuantityChangeMode.TOTAL)) as FarmTaskDefinition
        assertEquals(4,next.quantity); assertEquals(d.work,next.work)
    }
    @Test fun immutableFarmArchiveRetainsPhysicalSeedAndYieldEvidence() {
        val (d,s)=deliveredCarrots()
        val report=TaskObjectiveReport(UUID(0,7),0,d,3,s.resources.physical.entries,s.deliveries.toMap(),"one real crop cycle",farming=FarmObjectiveEvidence.capture(s))
        val tag=TaskObjectiveCodec.write(report); assertEquals(report,TaskObjectiveCodec.read(tag))
        s.planted.clear(); assertEquals(1,checkNotNull(report.farming).read(d).planted[cell])
        tag.getCompound("farming").getList("pickups",10).clear()
        assertFailsWith<IllegalArgumentException> { TaskObjectiveCodec.read(tag) }
    }
}
