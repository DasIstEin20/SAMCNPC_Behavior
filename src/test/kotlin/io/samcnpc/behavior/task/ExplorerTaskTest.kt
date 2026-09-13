package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class ExplorerTaskTest {
    private val at=NpcPosition(-0.5,65.0,-0.5)
    private fun definition()=ExplorerTaskDefinition("minecraft:overworld",at,radius=16,maxCells=4,budget=TaskBudget(1200))
    private fun record(): TaskRecord {
        val d=definition();val record=TaskRecord.start(UUID(0,1),d,emptyList())
        record.primary.explorer=ExplorerTaskState(mutableListOf(ExplorerNode(0,0,at.y,-1,1),
            ExplorerNode(1,0,at.y,0,2)),cursor=1,pending=ExplorerNode(1,-1,at.y,1))
        record.advanceTime(117);return record
    }
    private fun file(record: TaskRecord,version: Int)=CompoundTag().apply {
        putInt("version",version);put("tasks",ListTag().apply { add(TaskCodec.write(record)) })
    }
    @Test fun expeditionGeometryMemoryAndTimeAreBounded() {
        val d=definition();assertNull(d.validationProblem())
        for (bad in listOf(d.copy(radius=7),d.copy(radius=97),d.copy(cellStep=0),d.copy(cellStep=9),
            d.copy(maxCells=0),d.copy(maxCells=65),d.copy(verticalRange=0),d.copy(verticalRange=17),
            d.copy(heading=4),d.copy(chunkBudget=8),d.copy(chunkBudget=257),d.copy(budget=TaskBudget(72001)),
            d.copy(anchor=at.copy(x=Double.NaN)),d.copy(anchor=at.copy(z=29_999_980.0)))) assertNotNull(bad.validationProblem())
    }
    @Test fun chunkFootprintIncludesNegativeCoordinatesAndActivityMargin() {
        val d=definition();assertEquals(25,d.chunkFootprint())
        assertEquals(16,d.copy(anchor=at.copy(x=8.0,z=8.0),radius=8).chunkFootprint())
        assertEquals(16,d.copy(anchor=at.copy(x=-0.5,z=-0.5),radius=8).chunkFootprint())
        assertNotNull(d.copy(chunkBudget=24).validationProblem())
        assertNull(d.copy(chunkBudget=25).validationProblem())
        assertTrue(d.bounds.contains(d.position(ExplorerNode(-4,-4,at.y,-1))))
        assertFalse(d.bounds.contains(d.position(ExplorerNode(-5,-4,at.y,-1))))
        assertFalse(d.routeBounds.contains(d.position(ExplorerNode(4,0,at.y,0))))
        assertTrue(d.routeBounds.contains(d.position(ExplorerNode(3,0,at.y,0))))
    }
    @Test fun pausedPendingLegRoundTripsWithoutFreshBudgetOrRouteHandles() {
        val r=record();r.pause();val tag=TaskCodec.write(r);val restored=TaskCodec.read(tag)
        assertEquals(tag,TaskCodec.write(restored));assertEquals(1083,restored.primary.remainingTicks)
        val state=assertNotNull(restored.primary.explorer);assertEquals(2,state.nodes.size);assertEquals(1,state.depth())
        assertEquals(ExplorerNode(1,-1,at.y,1),state.pending);assertEquals(2,state.nodes[1].tried)
        for (name in listOf("navigationId","path","tickets","generation")) assertFalse(tag.toString().contains(name))
    }
    @Test fun malformedTreeCannotCreateLoopsDuplicatesOrOverflowCoordinates() {
        val d=definition();val state=assertNotNull(record().primary.explorer);val original=ExplorerTaskCodec.write(state)
        fun bad(change: (CompoundTag)->Unit) { val tag=original.copy();change(tag);assertFailsWith<IllegalArgumentException> { ExplorerTaskCodec.read(tag,d) } }
        bad { it.getList("nodes",10).getCompound(1).putInt("parent",1) }
        bad { it.getList("nodes",10).getCompound(1).putInt("x",0) }
        bad { it.getList("nodes",10).getCompound(1).putInt("x",Int.MIN_VALUE) }
        bad { it.getList("nodes",10).getCompound(0).putInt("tried",0) }
        bad { it.getList("nodes",10).getCompound(1).putDouble("y",Double.NaN) }
        bad { it.putInt("cursor",2) }
        bad { it.putInt("rejectedLegs",3) }
    }
    @Test fun pendingNodeMustComeFromTheCurrentAttemptedUnvisitedNeighbor() {
        val d=definition();val original=ExplorerTaskCodec.write(assertNotNull(record().primary.explorer))
        for (change in listOf<(CompoundTag)->Unit>(
            { it.putInt("parent",0) },{ it.putInt("x",0);it.putInt("z",0) },
            { it.putInt("z",-2) },{ it.putInt("tried",1) },{ it.putDouble("y",at.y+2) },
        )) { val tag=original.copy();change(tag.getCompound("pending"));assertFailsWith<IllegalArgumentException> { ExplorerTaskCodec.read(tag,d) } }
    }
    @Test fun unknownDefinitionOrStateFieldsAndMissingStateAreRejected() {
        val original=TaskCodec.write(record())
        for (change in listOf<(CompoundTag)->Unit>({ it.remove("explorer") },
            { it.getCompound("explorer").putString("script","denied") },
            { it.getCompound("definition").putString("url","denied") },
            { it.getCompound("explorer").putInt("phase",1) })) {
            val tag=original.copy();change(tag.getList("frames",10).getCompound(0))
            assertFailsWith<IllegalArgumentException> { TaskCodec.read(tag) }
        }
    }
    @Test fun completionRequiresAnActualAnchorObservationAndConsistentStop() {
        val r=record();val s=assertNotNull(r.primary.explorer)
        s.phase=ExplorerPhase.DONE;s.pending=null;s.cursor=0;s.stop=ExplorerStop.RETURN_RESERVE
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(r)) }
        r.completeActive(TaskReason.EXPLORATION_FINISHED,"returned")
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(r)) }
        r.reconciledPosition=at.copy(x=at.x+5)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(r)) }
        r.reconciledPosition=at;assertEquals(TaskCodec.write(r),TaskCodec.write(TaskCodec.read(TaskCodec.write(r))))
        s.stop=ExplorerStop.CELL_LIMIT;assertNotNull(s.validationProblem(definition()))
        s.stop=ExplorerStop.FRONTIER_EXHAUSTED;assertNotNull(s.validationProblem(definition()))
    }
    @Test fun interruptedReturnPreservesWaypointsAndSpendsOriginalTime() {
        val r=record();val s=assertNotNull(r.primary.explorer);s.pending=null;s.stop=ExplorerStop.RETURN_RESERVE;s.phase=ExplorerPhase.RETURN_RECOVER
        assertNull(r.interrupt(NavigateTaskDefinition("minecraft:overworld",at.copy(z=2.0),budget=TaskBudget(200))))
        r.advanceTime(31);r.pause();r.advanceTime(50)
        val restored=TaskCodec.read(TaskCodec.write(r));assertEquals(1052,restored.primary.remainingTicks)
        assertEquals(169,restored.active.remainingTicks);assertEquals(s.nodes,restored.primary.explorer?.nodes)
        restored.resume();restored.completeActive();assertEquals(ExplorerPhase.RETURN_RECOVER,restored.primary.explorer?.phase)
        assertEquals(1052,restored.active.remainingTicks)
    }
    @Test fun legacyAndFutureFilesPreserveUninterpretableExplorerRecords() {
        val r=record()
        for (version in listOf(7,10)) {
            val file=file(r,version);val store=TaskStore.load(file)
            assertNull(store.get(r.npcUuid));assertNotNull(store.problemFor(r.npcUuid));assertEquals(file,store.save(CompoundTag()))
        }
    }
    @Test fun v7FishingMigratesWithoutChangingTaskIdentityReceiptsOrBudget() {
        val d=FishingTaskDefinition("minecraft:overworld",io.samcnpc.core.api.NpcBlockPosition(3,64,0),at,2,at,budget=TaskBudget(1000))
        val r=TaskRecord.start(UUID(0,2),d,emptyList())
        r.primary.fishing=FishingTaskState(HarvestResources(mapOf(FishingTaskDefinition.ROD to 1)),FishingPhase.WAIT,casts=1)
        r.advanceTime(251);r.pause()
        val store=TaskStore.load(file(r,7));val loaded=assertNotNull(store.get(r.npcUuid))
        assertEquals(TaskCodec.write(r),TaskCodec.write(loaded));assertEquals(749,loaded.primary.remainingTicks)
        assertEquals(9,store.save(CompoundTag()).getInt("version"));assertNull(store.problemFor(r.npcUuid))
    }
}
