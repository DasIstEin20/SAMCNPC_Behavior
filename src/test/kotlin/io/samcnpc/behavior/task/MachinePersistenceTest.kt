package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class MachinePersistenceTest {
    private fun record(): TaskRecord {
        val endpoint=NpcContainerEndpoint("minecraft:overworld",NpcBlockPosition(8,65,0))
        val d=MachineTaskDefinition(endpoint.dimensionId,MachineFeeds(listOf(MachinePort(endpoint.copy(side=NpcBlockFace.UP),0,"minecraft:raw_iron",4))),
            MachinePort(endpoint.copy(side=NpcBlockFace.DOWN),0,"minecraft:iron_ingot",4),NpcPosition(0.5,65.0,0.5))
        val r=HarvestResources(mapOf("minecraft:raw_iron" to 4))
        check(r.observeTransfer(mapOf("minecraft:raw_iron" to 1),"minecraft:raw_iron",3,true) == null)
        check(r.observeTransfer(mapOf("minecraft:raw_iron" to 1,"minecraft:iron_ingot" to 2),"minecraft:iron_ingot",2,false) == null)
        val task=TaskRecord.start(UUID(0,1),d,emptyList())
        task.primary.machine=MachineTaskState(r,intArrayOf(3),listOf(MachinePortShape("minecraft:furnace",1),MachinePortShape("minecraft:furnace",2)),
            collected=2,cursor=1,pollRemaining=10,idleTicks=95,phase=MachinePhase.WORK,transfers=2)
        task.advanceTime(17);task.pause();return task
    }
    @Test fun partialMachineContractAndItsRemainingClocksRoundTripWithoutLiveHandles() {
        val task=record();val tag=TaskCodec.write(task);val restored=TaskCodec.read(tag)
        assertEquals(tag,TaskCodec.write(restored));assertEquals(task.primary.definition,restored.primary.definition)
        val state=assertNotNull(restored.primary.machine)
        assertEquals(listOf(3),state.supplied.toList());assertEquals(2,state.collected);assertEquals(112,state.idleTicks)
        assertEquals(5983,restored.primary.remainingTicks);assertTrue(state.resources.mustReconcileLoad);assertNull(state.observedSlots)
        assertNull(state.resources.observedLoadGeneration)
    }
    @Test fun forgedProgressMissingStateAndUnknownFieldsAreRejected() {
        val tag=TaskCodec.write(record())
        val missing=tag.copy();missing.getList("frames",10).getCompound(0).remove("machine")
        val forged=tag.copy();forged.getList("frames",10).getCompound(0).getCompound("machine").putIntArray("supplied",intArrayOf(4))
        val output=tag.copy();output.getList("frames",10).getCompound(0).getCompound("machine").putInt("collected",3)
        val extra=tag.copy();extra.getList("frames",10).getCompound(0).getCompound("machine").putString("script","forbidden")
        val done=tag.copy();done.putString("status","COMPLETED");done.putString("reason","MACHINE_FINISHED")
        for (candidate in listOf(missing,forged,output,extra,done)) assertFailsWith<IllegalArgumentException> { TaskCodec.read(candidate) }
    }
    @Test fun preMachineAndFutureStoresPreserveRejectedMachineDataVerbatim() {
        for (version in listOf(5,11)) {
            val t=record();val file=CompoundTag();file.putInt("version",version);file.put("tasks",ListTag().apply { add(TaskCodec.write(t)) })
            val store=TaskStore.load(file);assertNull(store.get(t.npcUuid));assertNotNull(store.problemFor(t.npcUuid));assertEquals(file,store.save(CompoundTag()))
        }
        val t=record();val file=CompoundTag();file.putInt("version",6);file.put("tasks",ListTag().apply { add(TaskCodec.write(t)) })
        assertEquals(t.report(),assertNotNull(TaskStore.load(file).get(t.npcUuid)).report())
    }
    @Test fun loadedInventoryMismatchCannotBeReclassifiedAsFreshMachineProgress() {
        val state=assertNotNull(TaskCodec.read(TaskCodec.write(record())).primary.machine);val before=state.resources.entries
        assertNotNull(state.resources.reconcileLoad(null,mapOf("minecraft:raw_iron" to 4,"minecraft:iron_ingot" to 2)))
        assertTrue(state.resources.uncertain);assertEquals(before,state.resources.entries)
    }
}
