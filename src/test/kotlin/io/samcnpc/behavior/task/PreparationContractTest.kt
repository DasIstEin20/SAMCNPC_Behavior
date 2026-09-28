package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import net.minecraft.nbt.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class PreparationContractTest {
    private val position = NpcPosition(0.5,64.0,0.5)
    private val source = ContainerChoices(listOf(NpcBlockPosition(2,64,0)))
    private fun work() = EnsureItems(ItemQuery.parse("@axe|@pickaxe"),1,source,0.2,NpcEquipmentDestination.MAIN_HAND,1)
    private fun record(): TaskRecord {
        val definition=InventoryTaskDefinition("minecraft:overworld",work(),position,version=3)
        return TaskRecord.start(UUID(0,55),definition,emptyList()).also {
            it.primary.inventory=InventoryWorkState(HarvestResources(emptyMap()),emptyMap(),0,600)
        }
    }
    @Test fun preparationAndFreeSpaceRoundTripWithoutWideningLegacyDocuments() {
        val work=work(); val encoded=InventoryWorkCodec.write(work)
        assertEquals(encoded,InventoryWorkCodec.write(InventoryWorkCodec.read(encoded)))
        assertNotNull(InventoryTaskDefinition("minecraft:overworld",work,position,version=2).validationProblem())
        val unload=UnloadExcess(listOf(ItemReserve("minecraft:cobblestone",64)),source,2)
        val policy=TaskLogisticsPolicy(anchor=position,unload=unload,preparation=work)
        assertNull(policy.validationProblem())
        val tag=InventoryWorkCodec.writePolicy(policy)
        assertEquals(tag,InventoryWorkCodec.writePolicy(InventoryWorkCodec.readPolicy(tag)))
        assertNotNull(EnsureItems(ItemQuery.Exact("minecraft:coal"),2,source,destination=NpcEquipmentDestination.MAIN_HAND).validationProblem())
        assertNotNull(EnsureItems(ItemQuery.parse("@axe"),1,source,Double.NaN).validationProblem())
    }
    @Test fun admittedItemIdentitiesAndQuotaRemainBoundedAcrossPauseReload() {
        val record=record(); val state=checkNotNull(record.primary.inventory); val work=work()
        repeat(16) { assertTrue(state.admitPreparationItem(work,"test:tool_$it")) }
        assertFalse(state.admitPreparationItem(work,"test:overflow"))
        assertTrue(state.admitPreparationItem(work,"test:tool_1"))
        assertFalse(state.admitPreparationItem(work,"bad id"))
        record.advanceTime(25); record.pause()
        val tag=TaskCodec.write(record); val loaded=TaskCodec.read(tag)
        assertEquals(tag,TaskCodec.write(loaded)); assertEquals(1175,loaded.primary.remainingTicks)
        val invalid=InventoryStateCodec.write(state)
        invalid.put("goals",LumberjackTaskCodec.counts(state.goals + ("test:tool_1" to 2)))
        assertFailsWith<IllegalArgumentException> { InventoryStateCodec.read(invalid,loaded.primary.definition as InventoryTaskDefinition) }
        val stateTag=InventoryStateCodec.write(state); stateTag.putString("script","forbidden")
        assertFailsWith<IllegalArgumentException> { InventoryStateCodec.read(stateTag,loaded.primary.definition as InventoryTaskDefinition) }
    }
    @Test fun completedPreparationNeedsFreshReadinessAndPhysicalReturnBeyondASuccessLabel() {
        val record=record(); val state=checkNotNull(record.primary.inventory)
        state.returning(InventoryWorkReason.SATISFIED,"observed equipment")
        record.reconciledPosition=position; record.endInventory(true,message=state.detail)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(record)) }
        state.readiness=InventoryReadiness(1,true,35)
        assertEquals(record.report(),TaskCodec.read(TaskCodec.write(record)).report())
        state.readiness=InventoryReadiness(1,false,35)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(record)) }
        state.readiness=InventoryReadiness(0,true,35)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(record)) }
    }
    @Test fun preV11PreparationIsPreservedWithoutInterpretationAndV11Reloads() {
        val record=record();record.pause()
        for (version in listOf(10,12)) {
            val tag=CompoundTag().apply { putInt("version",version);put("tasks",ListTag().apply { add(TaskCodec.write(record)) }) }
            val store=TaskStore.load(tag);assertNull(store.get(record.npcUuid));assertEquals(tag,store.save(CompoundTag()))
        }
        val tag=CompoundTag().apply { putInt("version",11);put("tasks",ListTag().apply { add(TaskCodec.write(record)) }) }
        assertEquals(record.report(),assertNotNull(TaskStore.load(tag).get(record.npcUuid)).report())
    }
}
