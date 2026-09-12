package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class PlantingTaskTest {
    private val cell=NpcBlockPosition(4,64,0)
    private fun definition(species: SaplingSpecies=SaplingSpecies.OAK,mode: PlantingMode=PlantingMode.PATCH): PlantingTaskDefinition {
        val max=cell.copy(x=cell.x+species.layoutSize-1,z=cell.z+species.layoutSize-1)
        return PlantingTaskDefinition("minecraft:overworld",PlantingWorkOrder(WorkArea(WorkBox(cell,max)),species,mode),1,NpcPosition(0.5,64.0,0.5))
    }
    @Test fun offeredSpeciesAndModesRoundTripOnlyBoundedAuthorizedLayouts() {
        for(species in SaplingSpecies.entries) for(mode in PlantingMode.entries) {
            val d=definition(species,mode)
            assertNull(d.validationProblem()); assertEquals(listOf(cell),d.work.sites)
            assertEquals(d,TaskCodec.readDefinition(TaskCodec.writeDefinition(d)))
            assertEquals(species.layoutSize*species.layoutSize,species.footprint(cell).size)
        }
        val d=definition(SaplingSpecies.DARK_OAK)
        assertNotNull(d.copy(work=PlantingWorkOrder(WorkArea(WorkBox(cell,cell)),SaplingSpecies.DARK_OAK,PlantingMode.PATCH)).validationProblem())
        assertNotNull(d.copy(quantity=129).validationProblem())
        assertNotNull(d.copy(work=PlantingWorkOrder(d.work.area,d.work.species,d.work.mode,spacing=3)).validationProblem())
    }
    @Test fun explicitSitesAndExclusionsCannotOverlapOrPartiallyEscapeTwoByTwoPermission() {
        val species=SaplingSpecies.DARK_OAK; val area=WorkArea(WorkBox(cell,cell.copy(x=15,z=3)))
        assertNotNull(PlantingWorkOrder(area,species,PlantingMode.GAPS,positions=listOf(cell,cell)).validationProblem())
        assertNotNull(PlantingWorkOrder(area,species,PlantingMode.GAPS,positions=listOf(cell,cell.copy(x=6))).validationProblem())
        assertNotNull(PlantingWorkOrder(area,species,PlantingMode.GAPS,positions=listOf(cell.copy(x=15))).validationProblem())
        val excluded=cell.copy(x=5,z=1)
        val w=PlantingWorkOrder(WorkArea(area.bounds,listOf(WorkBox(excluded,excluded))),species,PlantingMode.PATCH)
        assertTrue(cell !in w.sites && cell.copy(x=10) in w.sites)
    }
    private fun partial(): Pair<PlantingTaskDefinition,PlantingTaskState> {
        val d=definition(SaplingSpecies.DARK_OAK,PlantingMode.GAPS); val item=d.work.species.blockId
        val s=PlantingTaskState(HarvestResources(mapOf(item to 4)))
        val plot=PlantingPlot(cell,listOf(cell)); val placed=cell.copy(x=5)
        assertNull(s.resources.observeStep(mapOf(item to 3),emptyMap(),emptyMap(),placement=true))
        plot.placed.add(placed); s.plots[cell]=plot; s.cursor=1; s.selected=cell; s.phase=PlantingPhase.PLACE
        return d to s
    }
    @Test fun partialQuadPersistsExactMembersAndConsumedSaplingsAcrossPauseAndInterruption() {
        val (d,s)=partial(); val r=TaskRecord.start(UUID(0,1),d,emptyList()); r.primary.planting=s
        r.advanceTime(91); assertNull(r.interrupt(NavigateTaskDefinition(d.dimensionId,d.anchor)))
        r.advanceTime(19); r.pause()
        val tag=TaskCodec.write(r); val loaded=TaskCodec.read(tag)
        assertEquals(tag,TaskCodec.write(loaded)); assertEquals(d.budget.ticks-110,loaded.primary.remainingTicks)
        assertTrue(checkNotNull(loaded.primary.planting).reconcileWorld); assertEquals(1,loaded.primary.planting?.planted())
        assertFalse(s.goal(d)); assertFailsWith<IllegalArgumentException> { TaskCodec.read(tag,4) }
    }
    @Test fun fabricatedPlacementAndInitialLayoutCannotBecomeQuota() {
        val (d,s)=partial()
        s.plots.getValue(cell).placed.add(cell.copy(z=1))
        assertFailsWith<IllegalArgumentException> { PlantingStateCodec.read(PlantingStateCodec.write(s),d) }
        s.plots.clear(); s.plots[cell]=PlantingPlot(cell,d.work.species.footprint(cell))
        assertEquals(0,s.completed(d)); assertFailsWith<IllegalArgumentException> { PlantingStateCodec.read(PlantingStateCodec.write(s),d) }
    }
    private fun complete(): Pair<PlantingTaskDefinition,PlantingTaskState> {
        val (d,s)=partial(); val item=d.work.species.blockId
        for(p in d.work.species.footprint(cell).filter { it !in s.plots.getValue(cell).initial && it !in s.plots.getValue(cell).placed }) {
            val count=checkNotNull(s.resources.retained()[item])-1
            assertNull(s.resources.observeStep(mapOf(item to count),emptyMap(),emptyMap(),placement=true)); s.plots.getValue(cell).placed.add(p)
        }
        s.phase=PlantingPhase.DONE; s.selected=null
        return d to s
    }
    @Test fun completedQuadRequiresAllRealMembersAndReportsSaplingsSeparatelyFromLayouts() {
        val (d,s)=complete(); assertEquals(3,s.planted()); assertEquals(1,s.completed(d)); assertTrue(s.goal(d))
        val r=TaskRecord.start(UUID(0,1),d,emptyList()); r.primary.planting=s; r.finish(TaskStatus.COMPLETED,TaskReason.PLANTING_FINISHED,"three real saplings completed one existing gap")
        assertEquals(r.report(),TaskCodec.read(TaskCodec.write(r)).report())
        s.plots.getValue(cell).placed.remove(cell.copy(z=1)); assertFalse(s.goal(d))
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(r)) }
    }
    @Test fun plantingArchiveIsFrozenAndCannotImportAnotherOperationRecursively() {
        val (d,s)=complete()
        val report=TaskObjectiveReport(UUID(0,7),0,d,1,s.resources.entries,emptyMap(),"one completed layout",planting=PlantingObjectiveEvidence.capture(s,d))
        val tag=TaskObjectiveCodec.write(report); assertEquals(report,TaskObjectiveCodec.read(tag))
        s.plots.clear(); assertEquals(3,checkNotNull(report.planting).read().planted())
        tag.getCompound("planting").getCompound("definition").putString("id",LumberjackTaskDefinition.ID)
        assertFailsWith<IllegalArgumentException> { TaskObjectiveCodec.read(tag) }
    }
    @Test fun partialLayoutDefersQuantityChangesWithoutBlockingExplicitTimeExtension() {
        val (d,s)=partial(); val r=TaskRecord.start(UUID(0,1),d,emptyList()); r.primary.planting=s
        assertFalse(TaskAmendmentPreparation.boundary(r,TaskChange.Quantity(2,QuantityChangeMode.TOTAL)))
        assertTrue(TaskAmendmentPreparation.boundary(r,TaskChange.ExtendTime(50)))
        assertEquals(2,(TaskChanges.proposed(d,TaskChange.Quantity(2,QuantityChangeMode.TOTAL)) as PlantingTaskDefinition).quantity)
        assertEquals(d.budget.ticks+50,TaskChanges.proposed(d,TaskChange.ExtendTime(50)).budget.ticks)
    }
    @Test fun woodReplantNeedsConfirmedWholeCutLayoutsAndRetainsSuppliedSpacing() {
        val d=definition(SaplingSpecies.DARK_OAK,PlantingMode.GAPS)
        val cells=d.work.species.footprint(cell)
        assertTrue(WoodReplant.eligibleBases(d,cells.dropLast(1).toSet()).isEmpty())
        assertEquals(listOf(cell),WoodReplant.eligibleBases(d,cells.toSet()+cell.copy(y=65)))
        val actual=d.copy(work=PlantingWorkOrder(d.work.area,d.work.species,d.work.mode,d.work.spacing,listOf(cell)))
        assertTrue(WoodReplant.matchesRequested(actual,d,cells.toSet()))
        assertFalse(WoodReplant.matchesRequested(actual,d,cells.dropLast(1).toSet()))
    }
}
