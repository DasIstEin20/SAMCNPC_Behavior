package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class FoodTaskTest {
    private val bread="minecraft:bread"
    private val area=WorkArea(WorkBox(NpcBlockPosition(2,64,0),NpcBlockPosition(5,66,2)))
    private val recipient=NpcBlockPosition(0,64,3)
    private fun definition(work: FoodWorkOrder=FoodWorkOrder.Drops(area),quantity: Int=3,keep: Int=0)=FoodTaskDefinition(
        "minecraft:overworld",work,WorkResourceIds(listOf(bread)),ContainerChoices(listOf(recipient)),quantity,keep,NpcPosition(0.5,64.0,0.5))
    @Test fun actualPickupAndProtectedGiftsKeepRationSeparateFromDelivery() {
        val d=definition(keep=9)
        val s=FoodTaskState(ProducedResources.initial(mapOf(bread to 5)))
        s.knownFood.add(bread)
        assertNull(s.resources.observeLive(mapOf(bread to 8),ProducedGain.STOCK))
        assertEquals(0,s.deliverable(d)); assertFalse(s.enough(d))
        assertNull(s.resources.pickup(bread,4,mapOf(bread to 12))); s.pickups[bread]=4
        assertEquals(3,s.deliverable(d)); assertTrue(s.enough(d)); assertFalse(s.goal(d))
        val observation=ContainerTransferObservation(recipient,bread,ContainerTransferDirection.DEPOSIT,3,12,9,0,3,27,"minecraft:chest")
        assertNull(s.resources.transfer(observation,ProducedTransfer.DELIVERY,mapOf(bread to 9),ProducedGain.STOCK))
        s.checkpoints[recipient]=ContainerCheckpoint("minecraft:chest",27); s.deliveries[recipient]=mapOf(bread to 3)
        assertEquals(3,s.delivered(d)); assertEquals(9,s.retainedFood()); assertTrue(s.goal(d))
        val restored=FoodStateCodec.read(FoodStateCodec.write(s),d)
        assertEquals(FoodStateCodec.write(s),FoodStateCodec.write(restored))
        assertEquals(3,restored.resources.entries.getValue(bread).protectedGathered)
    }
    @Test fun authorizedSourceWithdrawalIsCargoButSuppliesAndIncidentalGainsAreNot() {
        val source=NpcBlockPosition(3,64,0)
        val d=definition(FoodWorkOrder.Stored(ContainerChoices(listOf(source)),2))
        val s=FoodTaskState(ProducedResources.initial(mapOf(bread to 4))); s.knownFood.add(bread)
        assertNull(s.resources.observeLive(mapOf(bread to 6),ProducedGain.STOCK))
        val transfer=ContainerTransferObservation(source,bread,ContainerTransferDirection.WITHDRAW,3,6,9,7,4,27,"minecraft:chest")
        assertNull(s.resources.transfer(transfer,ProducedTransfer.OUTPUT_SOURCE,mapOf(bread to 9),ProducedGain.STOCK))
        s.withdrawals[source]=mapOf(bread to 3); s.sourceCheckpoints[source]=ContainerCheckpoint("minecraft:chest",27)
        assertEquals(3,s.deliverable(d))
        val tag=FoodStateCodec.write(s)
        assertEquals(tag,FoodStateCodec.write(FoodStateCodec.read(tag,d)))
        tag.getList("withdrawals",10).clear()
        assertFailsWith<IllegalArgumentException> { FoodStateCodec.read(tag,d) }
    }
    @Test fun consumedFoodNeverBecomesDeliveredQuotaAndCannotForgeCompletion() {
        val d=definition(); val s=FoodTaskState(ProducedResources.initial(emptyMap())); s.knownFood.add(bread)
        assertNull(s.resources.pickup(bread,3,mapOf(bread to 3))); s.pickups[bread]=3
        assertNull(s.resources.consume(bread,1,mapOf(bread to 2)))
        assertEquals(1,s.resources.physical.entries.getValue(bread).consumed)
        assertEquals(0,s.delivered(d)); assertEquals(2,s.deliverable(d)); assertFalse(s.goal(d))
        val record=TaskRecord.start(UUID(0,1),d,emptyList()); record.primary.food=s
        s.phase=FoodPhase.RETURN; record.finish(TaskStatus.COMPLETED,TaskReason.FOOD_FINISHED,"forged")
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(record)) }
    }
    @Test fun explicitHuntRequiresNonemptyPermissionAndCannotBeInjectedIntoGathering() {
        assertNotNull(definition(FoodWorkOrder.Hunt(area,NpcEntityTypeFilter.ANY)).validationProblem())
        assertNotNull(definition(FoodWorkOrder.Hunt(area,NpcEntityTypeFilter.of(setOf("minecraft:player")))).validationProblem())
        val hunt=definition(FoodWorkOrder.Hunt(area,NpcEntityTypeFilter.of(setOf("minecraft:cow")),2))
        assertNull(hunt.validationProblem()); assertEquals(hunt,TaskCodec.readDefinition(TaskCodec.writeDefinition(hunt)))
        val state=FoodTaskState(ProducedResources.initial(emptyMap()))
        state.phase=FoodPhase.HUNT; state.huntTarget=UUID(0,4); state.huntFrame=UUID(0,5); state.collectionOrigin=NpcPosition(3.0,64.0,1.0)
        assertFailsWith<IllegalArgumentException> { FoodStateCodec.read(FoodStateCodec.write(state),definition()) }
    }
    @Test fun pauseAndReloadRetainFoodIntentAndOriginalTimeWithoutTransientControls() {
        val d=definition(FoodWorkOrder.Berries(area)).copy(outputs=WorkResourceIds(listOf(FoodTaskDefinition.BERRY_ITEM)))
        val record=TaskRecord.start(UUID(0,1),d,emptyList()); val s=FoodTaskState(ProducedResources.initial(emptyMap())); record.primary.food=s
        s.phase=FoodPhase.BERRY; s.berry=area.bounds.min; s.cursor=1
        record.advanceTime(121); record.pause()
        val tag=TaskCodec.write(record); val restored=TaskCodec.read(tag)
        assertEquals(tag,TaskCodec.write(restored)); assertEquals(5879,restored.primary.remainingTicks)
        assertTrue(checkNotNull(restored.primary.food).resources.physical.mustReconcileLoad)
        assertFalse(tag.toString().contains("navigationId"))
        val wrong=tag.copy(); wrong.getList("frames",10).getCompound(0).getCompound("food").putString("exhausted","false")
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(wrong) }
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(tag,4) }
    }
    @Test fun sourceOverlapUnknownFieldsAndOversizedGeometryFailBeforeWork() {
        assertNotNull(definition(FoodWorkOrder.Stored(ContainerChoices(listOf(recipient)))).validationProblem())
        assertNotNull(definition().copy(keepFood=65).validationProblem())
        val d=definition(); val tag=TaskCodec.writeDefinition(d); tag.getCompound("work").putBoolean("allowHunt",true)
        assertFailsWith<IllegalArgumentException> { TaskCodec.readDefinition(tag) }
        val big=WorkArea(WorkBox(NpcBlockPosition(0,64,0),NpcBlockPosition(49,127,49)))
        assertNotNull(definition(FoodWorkOrder.Drops(big)).validationProblem())
    }
    @Test fun quantityAndRecipientAmendmentsWaitForPhysicalCollectionBoundary() {
        val d=definition(); val record=TaskRecord.start(UUID(0,1),d,emptyList()); val s=FoodTaskState(ProducedResources.initial(emptyMap())); record.primary.food=s
        val change=TaskChange.Quantity(7,QuantityChangeMode.TOTAL)
        s.phase=FoodPhase.COLLECT; assertFalse(TaskAmendmentPreparation.boundary(record,change))
        s.phase=FoodPhase.SELECT; assertTrue(TaskAmendmentPreparation.boundary(record,change))
        val revised=TaskChanges.proposed(d,change) as FoodTaskDefinition
        assertEquals(7,revised.quantity); assertEquals(d.work,revised.work); assertEquals(d.keepFood,revised.keepFood)
        assertEquals(d.copy(destinations=ContainerChoices(listOf(NpcBlockPosition(-1,64,3)))),TaskChanges.proposed(d,TaskChange.Redirect(ContainerChoices(listOf(NpcBlockPosition(-1,64,3))))))
    }
    @Test fun foodEvidenceArchiveIsImmutableAndChecksOriginAgainstPhysicalHistory() {
        val d=definition(); val s=FoodTaskState(ProducedResources.initial(emptyMap())); s.knownFood.add(bread)
        assertNull(s.resources.pickup(bread,2,mapOf(bread to 2))); s.pickups[bread]=2
        val report=TaskObjectiveReport(UUID(0,9),0,d,0,s.resources.physical.entries,emptyMap(),"physical food",food=FoodObjectiveEvidence.capture(s))
        val tag=TaskObjectiveCodec.write(report)
        assertEquals(report,TaskObjectiveCodec.read(tag))
        s.pickups.clear()
        assertEquals(2,checkNotNull(report.food).read(d).pickups[bread])
        tag.getCompound("food").getList("pickups",10).clear()
        assertFailsWith<IllegalArgumentException> { TaskObjectiveCodec.read(tag) }
    }
}
