package io.samcnpc.behavior.task

import io.samcnpc.behavior.runtime.PlanningWorldView
import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class MiningTaskTest {
    private val ore="minecraft:iron_ore"
    private val item="minecraft:raw_iron"
    private val first=NpcBlockPosition(0,64,0)
    private fun order(method: MiningMethod=MiningMethod.EXPOSED) = MiningWorkOrder(WorkArea(WorkBox(first,NpcBlockPosition(5,64,0))),method,WorkResourceIds(listOf(ore)))
    private fun definition(work: MiningWorkOrder=order()) = MiningTaskDefinition("minecraft:overworld",work,WorkResourceIds(listOf(item)),ContainerChoices(listOf(NpcBlockPosition(-2,64,0))),2,MiningCounting.DELIVERED_ITEMS,NpcPosition(0.5,64.0,0.5))
    @Test fun tunnelCoordinatesCoverEachExactCellOnceInEveryDirectionFromCeilingToFloor() {
        for (direction in TunnelDirection.entries) {
            val geometry=TunnelGeometry(first,direction,3,4,7)
            val cells=(0 until 84).map(geometry::cell)
            assertEquals(84,cells.toSet().size); assertTrue(cells.all(geometry.bounds()::contains))
            assertEquals(67,cells.first().y); assertEquals(64,cells[3].y)
            val work=MiningWorkOrder(WorkArea(geometry.bounds()),MiningMethod.TUNNEL,WorkResourceIds(listOf(ore)),WorkResourceIds(listOf("minecraft:stone")),geometry)
            assertNull(work.validationProblem()); assertEquals(cells,(0 until work.volume).map(work::cell))
        }
        assertFailsWith<IllegalArgumentException> { TunnelGeometry(first,TunnelDirection.EAST,0,2,3).cell(0) }
    }
    @Test fun deniedPlanningDoesNotObserveOrAdvanceAndExposureReusesSevenFacts() {
        val world=Grid(); world.allowed=false; world.blocks[first]=world.ore(first)
        val state=MiningSelectionState()
        assertEquals(MiningSelectionResult.Deferred,MiningSelection.next(order(),state,world)); assertEquals(0,state.cursor); assertEquals(0,world.reads)
        world.allowed=true
        assertEquals(MiningSelectionResult.Target(first,ore),MiningSelection.next(order(),state,world)); assertEquals(7,world.reads)
    }
    @Test fun veinOnlyExpandsFromConfirmedRemovalAndCannotJumpToDisconnectedOre() {
        val world=Grid(); val next=NpcBlockPosition(1,64,0); val remote=NpcBlockPosition(5,64,0)
        for (p in listOf(first,next,remote)) world.blocks[p]=world.ore(p)
        val state=MiningSelectionState(); val work=order(MiningMethod.VEIN)
        assertIs<MiningSelectionResult.Target>(MiningSelection.next(work,state,world)); assertTrue(state.frontier.isEmpty())
        state.confirmed(work,first,ore); world.blocks.remove(first)
        assertEquals(MiningSelectionResult.Target(next,ore),MiningSelection.next(work,state,world))
        state.confirmed(work,next,ore); world.blocks.remove(next)
        assertEquals(MiningSelectionResult.Exhausted,MiningSelection.next(work,state,world)); assertFalse(remote in state.removed)
    }
    @Test fun unknownFluidFallingAndUnbreakableFactsStopExplicitVolumeWork() {
        val work=order(MiningMethod.EXCAVATION)
        for (problem in listOf(MiningProblem.UNOBSERVABLE,MiningProblem.FLUID,MiningProblem.FALLING_BLOCK,MiningProblem.UNBREAKABLE,MiningProblem.CONTAINER)) {
            val world=Grid(); val base=world.ore(first)
            world.blocks[first]=when (problem) {
                MiningProblem.UNOBSERVABLE -> base.copy(environment=null)
                MiningProblem.FLUID -> base.copy(environment=checkNotNull(base.environment).copy(fluidId="minecraft:water"))
                MiningProblem.FALLING_BLOCK -> base.copy(environment=checkNotNull(base.environment).copy(falling=true))
                MiningProblem.UNBREAKABLE -> base.copy(environment=checkNotNull(base.environment).copy(destroySpeed=-1.0F))
                else -> base.copy(hasContainer=true)
            }
            assertEquals(MiningSelectionResult.Stop(problem,first),MiningSelection.next(work,MiningSelectionState(),world))
        }
        val world=Grid(); world.blocks[first]=world.ore(first).copy(blockId="minecraft:diamond_block")
        assertEquals(MiningSelectionResult.Stop(MiningProblem.DISALLOWED_BLOCK,first),MiningSelection.next(work,MiningSelectionState(),world))
    }
    @Test fun unknownNeighborAndExcludedCellsNeverBecomeRemovalPermission() {
        val world=Grid(); world.blocks[first]=world.ore(first)
        val neighbor=NpcBlockPosition(0,65,0); world.blocks[neighbor]=world.ore(neighbor).copy(environment=null)
        assertEquals(MiningProblem.UNOBSERVABLE,assertIs<MiningSelectionResult.Stop>(MiningSelection.next(order(),MiningSelectionState(),world)).reason)
        world.blocks.remove(neighbor)
        val work=order().copy(area=WorkArea(order().area.bounds,listOf(WorkBox(first,first))))
        assertEquals(MiningSelectionResult.Exhausted,MiningSelection.next(work,MiningSelectionState(),world))
        assertFailsWith<IllegalArgumentException> { MiningSelectionState().confirmed(work,first,ore) }
    }
    @Test fun finiteSelectionLimitIsExplicitAndNeverCreditsAnotherBlock() {
        val state=MiningSelectionState(); state.veinAnchor=first; state.removed[first]=ore; state.limited=true
        val result=MiningSelection.next(order(MiningMethod.VEIN),state,Grid())
        assertEquals(MiningSelectionResult.Stop(MiningProblem.SELECTION_LIMIT,null),result); assertEquals(1,state.removed.size)
    }
    @Test fun durablePendingIntentRoundTripsWithoutActionIdsAndForgedProgressIsRejected() {
        val record=TaskRecord.start(UUID(0,1),definition(),emptyList())
        val state=MiningTaskState(ProducedResources.initial(mapOf(item to 5))); record.primary.mining=state
        state.selection.cursor=1; state.phase=MiningPhase.WORK; state.target=MiningSelectionResult.Target(first,ore)
        record.advanceTime(123); record.pause()
        val tag=TaskCodec.write(record); val loaded=TaskCodec.read(tag)
        assertEquals(tag,TaskCodec.write(loaded)); assertEquals(5877,loaded.primary.remainingTicks)
        assertTrue(checkNotNull(loaded.primary.mining).reconcileWorld); assertTrue(checkNotNull(loaded.primary.mining).resources.physical.mustReconcileLoad)
        assertFalse(tag.toString().contains("blockActionId")); assertFalse(tag.toString().contains("navigationId"))
        val forged=tag.copy(); forged.putString("status","COMPLETED"); forged.putString("reason","MINING_FINISHED")
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(forged) }
        val outside=tag.copy(); mining(outside).getCompound("target").put("position",MiningOrderCodec.block(NpcBlockPosition(99,64,0)))
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(outside) }
        val wrong=tag.copy(); mining(wrong).putString("limited","false")
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(wrong) }
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(tag,4) }
    }
    @Test fun accessRemovalCannotSatisfyResourceQuotaAndClearanceRequiresAllPermittedCells() {
        val work=order(MiningMethod.EXCAVATION).copy(access=WorkResourceIds(listOf("minecraft:stone")))
        val d=definition(work).copy(counting=MiningCounting.REMOVED_RESOURCE_BLOCKS,quantity=1)
        val state=MiningTaskState(ProducedResources.initial(emptyMap()))
        state.selection.confirmed(work,first,"minecraft:stone"); assertFalse(state.goal(d)); assertEquals(0,state.confirmed(d))
        state.selection.confirmed(work,NpcBlockPosition(1,64,0),ore); assertTrue(state.goal(d))
        val clearance=d.copy(counting=MiningCounting.CLEARED_VOLUME,quantity=1); state.exhausted=true
        assertFalse(state.goal(clearance)); for (i in 0 until work.volume) state.selection.cleared.add(work.cell(i)); assertTrue(state.goal(clearance))
    }
    @Test fun amendmentsWaitForPhysicalBoundaryAndPreserveDeclaredCountingBasis() {
        val record=TaskRecord.start(UUID(0,1),definition(),emptyList()); val state=MiningTaskState(ProducedResources.initial(emptyMap())); record.primary.mining=state
        val quantity=TaskChange.Quantity(4,QuantityChangeMode.TOTAL)
        state.phase=MiningPhase.WORK; assertFalse(TaskAmendmentPreparation.boundary(record,quantity))
        state.phase=MiningPhase.COLLECT; assertFalse(TaskAmendmentPreparation.boundary(record,TaskChange.Redirect(definition().destinations)))
        state.phase=MiningPhase.SELECT; assertTrue(TaskAmendmentPreparation.boundary(record,quantity))
        val revised=TaskChanges.proposed(definition(),quantity) as MiningTaskDefinition
        assertEquals(4,revised.quantity); assertEquals(definition().counting,revised.counting); assertEquals(definition().work,revised.work)
    }
    @Test fun archivedEvidenceIsFrozenAndComparableAfterSerialization() {
        val d=definition(); val state=MiningTaskState(ProducedResources.initial(emptyMap()))
        state.selection.cursor=1; state.selection.confirmed(d.work,first,ore)
        val report=TaskObjectiveReport(UUID(0,9),0,d,0,state.resources.physical.entries,emptyMap(),"one actual block removed",MiningObjectiveEvidence.capture(state))
        val tag=TaskObjectiveCodec.write(report)
        assertEquals(report,TaskObjectiveCodec.read(tag))
        state.selection.removed.clear()
        assertEquals(1,checkNotNull(report.mining).read(d).selection.removed.size)
        tag.getCompound("mining").putInt("cursor",99999)
        assertEquals(1,checkNotNull(report.mining).read(d).selection.cursor)
        assertFailsWith<IllegalArgumentException> { TaskObjectiveCodec.read(tag) }
    }
    @Test fun commandResourceListsUseVanillaSerializableQuotedStringsAndStrictLiteralIds() {
        val reader=com.mojang.brigadier.StringReader("\"minecraft:iron_ore,minecraft:gold_ore\" - \"minecraft:raw_iron\"")
        val argument=com.mojang.brigadier.arguments.StringArgumentType.string()
        val first=argument.parse(reader)
        assertEquals(listOf("minecraft:iron_ore","minecraft:gold_ore"),io.samcnpc.behavior.command.TaskMiningCommands.ids(first).values)
        reader.skipWhitespace(); assertEquals("-",argument.parse(reader))
        reader.skipWhitespace(); assertEquals(listOf("minecraft:raw_iron"),io.samcnpc.behavior.command.TaskMiningCommands.ids(argument.parse(reader)).values); assertFalse(reader.canRead())
        assertFailsWith<IllegalArgumentException> { io.samcnpc.behavior.command.TaskMiningCommands.ids("minecraft:iron_ore,,minecraft:gold_ore") }
    }
    private fun mining(tag: CompoundTag)=tag.getList("frames",10).getCompound(0).getCompound("mining")
    private inner class Grid : NpcWorldView, PlanningWorldView {
        override val dimensionId="minecraft:overworld"
        val blocks=mutableMapOf<NpcBlockPosition,NpcBlockObservation>(); var reads=0; var allowed=true
        fun ore(p: NpcBlockPosition)=NpcBlockObservation(p,ore,false,true,false,NpcBlockEnvironment(3.0F,null,false,false,15))
        override fun admitPlanning(units: Int,kind: PlanningKind)=allowed
        override fun observeBlock(position: NpcBlockPosition)=observeBlockDetails(position)
        override fun observeBlockDetails(position: NpcBlockPosition): NpcBlockObservation { reads++; return blocks[position] ?: NpcBlockObservation(position,"minecraft:air",true,false,false,NpcBlockEnvironment(0.0F,null,false,true,15)) }
        override fun observeEntity(uuid: UUID): NpcEntityObservation?=error("unexpected entity observation")
        override fun queryEntities(query: NpcEntityQuery): List<NpcEntityObservation> = error("unexpected entity search")
        override fun observeBlockContainer(position: NpcBlockPosition): NpcBlockContainerObservation?=error("unexpected container")
        override fun raycast(request: NpcRaycastRequest): NpcRaycastResult=error("unexpected ray")
    }
}
