package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class FishingTaskTest {
    private val at=NpcPosition(0.5,65.0,0.5)
    private fun definition()=FishingTaskDefinition("minecraft:overworld",NpcBlockPosition(6,64,0),at,2,at,budget=TaskBudget(1000))
    private fun record(collecting: Boolean=false): TaskRecord {
        val record=TaskRecord.start(UUID(0,1),definition(),emptyList())
        val resources=HarvestResources(mapOf(FishingTaskDefinition.ROD to 1,"minecraft:cod" to 5))
        val state=FishingTaskState(resources,if (collecting) FishingPhase.COLLECT else FishingPhase.WAIT,casts=1)
        if (collecting) {
            assertNull(resources.observeLive(mapOf(FishingTaskDefinition.ROD to 1,"minecraft:cod" to 7)))
            state.caught=1;state.spawned["minecraft:cod"]=3;state.drops["minecraft:cod"]=3;state.collectionBaseline["minecraft:cod"]=0
        }
        record.primary.fishing=state;record.advanceTime(17);return record
    }
    @Test fun quotaWaterStanceAndTimeRemainBounded() {
        val d=definition();assertNull(d.validationProblem())
        for (bad in listOf(d.copy(catches=0),d.copy(catches=65),d.copy(travelRadius=Double.NaN),
            d.copy(water=NpcBlockPosition(100,64,0)),d.copy(standing=NpcPosition(0.5,80.0,0.5)),
            d.copy(pickupWaitTicks=1001),d.copy(returnTo=NpcPosition(100.0,65.0,0.0)))) assertNotNull(bad.validationProblem())
    }
    @Test fun waitingTaskRoundTripsWithoutAReanimatedHookOrFreshClock() {
        val record=record();record.pause();val tag=TaskCodec.write(record);val restored=TaskCodec.read(tag)
        assertEquals(tag,TaskCodec.write(restored));assertEquals(983,restored.primary.remainingTicks)
        val state=assertNotNull(restored.primary.fishing);assertEquals(FishingPhase.WAIT,state.phase);assertEquals(1,state.casts)
        assertTrue(state.resources.mustReconcileLoad);assertNull(state.resources.observedLoadGeneration)
        assertFalse(tag.toString().contains("hookUuid"));assertFalse(tag.toString().contains("fishingActionId"))
    }
    @Test fun partialCollectionPersistsActualReceiptsAndGrossPickupEvidence() {
        val record=record(true);val restored=TaskCodec.read(TaskCodec.write(record));val state=assertNotNull(restored.primary.fishing)
        assertEquals(17,state.collectTicks);assertEquals(1,state.caught);assertEquals(0,state.collectedCatches)
        assertEquals(mapOf("minecraft:cod" to 3),state.spawned);assertEquals(state.spawned,state.drops)
        assertEquals(0,state.collectionBaseline["minecraft:cod"]);assertEquals(2,state.resources.entries["minecraft:cod"]?.gathered)
        assertEquals(7,state.resources.retained()["minecraft:cod"])
        assertEquals(TaskCodec.write(record),TaskCodec.write(restored))
    }
    @Test fun checkpointInsidePayoutIsRejectedBeforeAnotherCatchCanExecute() {
        val record=record();assertNotNull(record.primary.fishing).pendingReel=true
        val error=assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(record)) }
        assertTrue(error.message.orEmpty().contains("unconfirmed fishing payout"))
        val file=CompoundTag();file.putInt("version",7);file.put("tasks",ListTag().apply { add(TaskCodec.write(record)) })
        val store=TaskStore.load(file);assertNull(store.get(record.npcUuid));assertNotNull(store.problemFor(record.npcUuid))
        assertEquals(file,store.save(CompoundTag()))
    }
    @Test fun fabricatedCountsMissingStateAndUnknownFieldsCannotLoad() {
        val original=TaskCodec.write(record(true))
        fun changed(change: (CompoundTag)->Unit): CompoundTag { val tag=original.copy();change(tag.getList("frames",10).getCompound(0));return tag }
        val candidates=listOf(changed { it.remove("fishing") },changed { it.getCompound("fishing").putInt("caught",3) },
            changed { it.getCompound("fishing").putInt("collectedCatches",1) },changed { it.getCompound("fishing").putString("script","denied") },
            changed { it.getCompound("fishing").putInt("collectTicks",1201) })
        for (tag in candidates) assertFailsWith<IllegalArgumentException> { TaskCodec.read(tag) }
        val r=record(true);val s=assertNotNull(r.primary.fishing);s.phase=FishingPhase.DONE;s.caught=2;s.collectedCatches=2;s.casts=2;s.drops.clear();s.collectionBaseline.clear()
        assertNotNull(s.validationProblem(r.primary.definition as FishingTaskDefinition))
    }
    @Test fun interruptedCollectionKeepsObligationWhilePrimaryTimeStillAdvances() {
        val record=record(true);val state=assertNotNull(record.primary.fishing)
        assertNull(record.interrupt(NavigateTaskDefinition("minecraft:overworld",at,budget=TaskBudget(200))))
        record.advanceTime(30);assertEquals(953,record.primary.remainingTicks);assertEquals(17,state.collectTicks)
        record.pause();record.advanceTime(50);assertEquals(953,record.primary.remainingTicks)
        record.resume();record.completeActive();record.advanceTime(10);assertEquals(27,state.collectTicks);assertEquals(943,record.primary.remainingTicks)
    }
    @Test fun previousAndFutureStoreVersionsCannotInterpretNewFishingRecords() {
        val r=record()
        for (version in listOf(6,9)) {
            val file=CompoundTag();file.putInt("version",version);file.put("tasks",ListTag().apply { add(TaskCodec.write(r)) })
            val store=TaskStore.load(file);assertNull(store.get(r.npcUuid));assertNotNull(store.problemFor(r.npcUuid));assertEquals(file,store.save(CompoundTag()))
        }
    }
}
