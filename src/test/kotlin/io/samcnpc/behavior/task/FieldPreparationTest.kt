package io.samcnpc.behavior.task

import com.google.gson.JsonParser
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.combat.CombatPolicyWorld
import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class FieldPreparationTest {
    private fun definition()=PrepareFieldTaskDefinition("minecraft:overworld",
        WorkArea(WorkBox(NpcBlockPosition(-8,62,-4),NpcBlockPosition(-6,62,-2))),NpcPosition(-10.5,63.0,-3.5),returnTo=NpcPosition(-10.5,63.0,-3.5))
    private fun record(): TaskRecord = TaskRecord.start(UUID(0,42),definition(),emptyList()).also {
        it.primary.fieldPreparation=FieldPreparationState(HarvestResources(mapOf("minecraft:iron_hoe" to 1)))
    }

    @Test fun soilPlaneBoundsExclusionsAndReturnAreValidatedBeforeExecution() {
        val d=definition();assertNull(d.validationProblem());assertEquals(9,d.cells.size)
        for(box in listOf(WorkBox(NpcBlockPosition(0,62,0),NpcBlockPosition(8,62,8)),
            WorkBox(NpcBlockPosition(0,62,0),NpcBlockPosition(1,63,1)),
            WorkBox(NpcBlockPosition(Int.MIN_VALUE,62,0),NpcBlockPosition(1,62,1))))
            assertNotNull(d.copy(area=WorkArea(box)).validationProblem())
        assertNotNull(d.copy(area=WorkArea(d.area.bounds,listOf(d.area.bounds))).validationProblem())
        assertNotNull(d.copy(returnTo=NpcPosition(100.0,63.0,0.0)).validationProblem())
        val partial=d.copy(area=WorkArea(d.area.bounds,listOf(WorkBox(d.cells.first(),d.cells.first()))))
        assertNull(partial.validationProblem());assertEquals(8,partial.cells.size)
    }

    @Test fun publicDocumentCarriesSoilCoordinatesWithoutInventingCropOrYield() {
        val json="""{"documentVersion":1,"type":"samcnpc:prepare_field","definitionVersion":1,"parameters":{
          "dimensionId":"minecraft:overworld","area":{"bounds":{"min":{"x":-8,"y":62,"z":-4},"max":{"x":-6,"y":62,"z":-2}}},
          "anchor":{"x":-10.5,"y":63,"z":-3.5},"returnTo":{"x":-10.5,"y":63,"z":-3.5}}}"""
        val order=assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(json)).value
        assertIs<OperationPrepareFieldOrder>(order);assertEquals(definition(),TaskPublicOrders.definition(order))
        for(key in listOf("quantity","crop","destination","prepareSoil")) {
            val invalid=JsonParser.parseString(json).asJsonObject;invalid["parameters"].asJsonObject.addProperty(key,1)
            assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(invalid.toString()))
        }
    }

    @Test fun restartPreservesExactUsesAndRequiresReconciliationWithoutAcceptingOldEnvelopes() {
        val r=record();val s=checkNotNull(r.primary.fieldPreparation);val d=definition()
        s.confirmed.add(d.cells.first());s.attempts[d.cells.first()]=1;s.cursor=1
        val saved=TaskCodec.write(r);val restored=TaskCodec.read(saved)
        val state=checkNotNull(restored.primary.fieldPreparation)
        assertTrue(state.reconcileWorld && state.resources.mustReconcileLoad)
        assertEquals(s.confirmed,state.confirmed);assertEquals(s.attempts,state.attempts)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(saved,9) }
        val incompatible=TaskFrame(UUID.randomUUID(),NavigateTaskDefinition(d.dimensionId,d.anchor))
        incompatible.fieldPreparation=state;assertNotNull(incompatible.stateProblem())
    }

    @Test fun savedCellsCannotEscapeAuthorizationSkipWorkOrManufactureCompletion() {
        val d=definition();val state=FieldPreparationState(HarvestResources(emptyMap()))
        val unauthorized=FieldPreparationCodec.write(state)
        unauthorized.put("confirmed",ListTag().apply { add(MiningOrderCodec.block(NpcBlockPosition(100,62,0))) })
        assertFailsWith<IllegalArgumentException> { FieldPreparationCodec.read(unauthorized,d) }
        for(phase in listOf("RETURN","VERIFY","DONE")) {
            val tag=FieldPreparationCodec.write(state);tag.putString("phase",phase)
            assertFailsWith<IllegalArgumentException> { FieldPreparationCodec.read(tag,d) }
        }
        val skipped=FieldPreparationCodec.write(state);skipped.putInt("cursor",1)
        assertFailsWith<IllegalArgumentException> { FieldPreparationCodec.read(skipped,d) }
        val r=record();r.status=TaskStatus.COMPLETED;r.reason=TaskReason.FIELD_PREPARED
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(r)) }
    }

    @Test fun completedFieldRequiresWholePlaneCertainInventoryAndActualReturn() {
        val r=record();val d=definition();val s=checkNotNull(r.primary.fieldPreparation)
        s.confirmed.addAll(d.cells);s.cursor=d.cells.size;s.phase=FieldPhase.DONE
        r.status=TaskStatus.COMPLETED;r.reason=TaskReason.FIELD_PREPARED;r.reconciledPosition=d.returnTo
        assertEquals(TaskStatus.COMPLETED,TaskCodec.read(TaskCodec.write(r)).status)
        r.reconciledPosition=NpcPosition(-7.0,63.0,-3.0)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(r)) }
        r.reconciledPosition=d.returnTo;s.resources.uncertain=true
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(r)) }
    }

    @Test fun hoeReceiptRejectsExtraConsumptionButAllowsTheLastToolToBreak() {
        fun used(before: Map<String,Int>,after: Map<String,Int>)=SoilPreparation.Result.Used(NpcActionResult.succeeded("test"),"minecraft:iron_hoe",before,after,true)
        assertNull(used(mapOf("minecraft:iron_hoe" to 1),emptyMap()).consumptionProblem())
        assertNotNull(used(mapOf("minecraft:iron_hoe" to 2),emptyMap()).consumptionProblem())
        assertNotNull(used(mapOf("minecraft:iron_hoe" to 1,"minecraft:dirt" to 1),mapOf("minecraft:iron_hoe" to 1)).consumptionProblem())
    }

    @Test fun timeAmendmentPreservesLiveGenerationSpentToolsAndCellAllowances() {
        val r=record();val d=definition();val s=checkNotNull(r.primary.fieldPreparation)
        s.resources.mustReconcileLoad=false;s.resources.observedLoadGeneration=UUID(0,99)
        assertNull(s.resources.observeStep(emptyMap(),emptyMap(),emptyMap(),placement=true))
        s.confirmed.add(d.cells.first());s.attempts[d.cells.first()]=1;s.cursor=1
        r.advanceTime(200)
        val npc=object: TestNpcFacade() {
            override fun snapshot()=decisionContext().snapshot.copy(position=d.anchor)
        }
        val request=TaskAmendmentRequest(r.id,UUID(0,101),UUID(0,2),0,100,200,TaskChange.ExtendTime(400))
        val updated=TaskAmendmentPreparation.prepare(r,request,npc,CombatPolicyWorld())
        val next=checkNotNull(updated.primary.fieldPreparation)
        assertFalse(next.resources.mustReconcileLoad);assertFalse(next.reconcileWorld)
        assertEquals(s.resources.observedLoadGeneration,next.resources.observedLoadGeneration)
        assertEquals(1,next.resources.entries["minecraft:iron_hoe"]?.consumed)
        assertEquals(s.attempts,next.attempts);assertEquals(6200,updated.primary.remainingTicks)
        assertFailsWith<IllegalArgumentException> { TaskAmendmentPreparation.proposed(r,request.copy(change=TaskChange.Replace(d)),npc.snapshot()) }
        TaskResumeObservation.prime(updated,npc)
        assertTrue(next.reconcileWorld);assertEquals(0,next.reconcileCursor)
        assertFalse(next.resources.mustReconcileLoad)
    }
}
