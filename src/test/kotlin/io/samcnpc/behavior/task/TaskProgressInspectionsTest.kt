package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskProgressInspectionsTest {
    @Test fun unavailableFishProgressIsDistinctFromZeroMeasuredCatchesAndSnapshotsStayDetached() {
        val frame = TaskFrame(UUID.randomUUID(), definition("fish"))
        assertSame(OperationProgress.NotInitialized, TaskProgressInspections.capture(frame))
        val state = FishingTaskState(HarvestResources(emptyMap()), casts = 3, caught = 2, collectedCatches = 1)
        frame.fishing = state
        val capture = observed(frame)
        assertEquals(3L, capture.count("casts").value)
        assertEquals(2L, capture.count("caught").value)
        assertEquals(1L, capture.count("collectedCatches").value)
        assertTrue(capture.reconciliationRequired == true)
        state.caught = 7
        assertEquals(2L, capture.count("caught").value)
        assertFailsWith<UnsupportedOperationException> { (capture.counters as MutableList).clear() }
    }

    @Test fun oneDarkOakLayoutIsFourPlacedSaplingsAndDoesNotCreditPreexistingTrees() {
        val base = NpcBlockPosition(0, 64, 0)
        val work = PlantingWorkOrder(WorkArea(WorkBox(base, NpcBlockPosition(4, 64, 4))),
            SaplingSpecies.DARK_OAK, PlantingMode.PATCH, 4, listOf(base))
        val d = PlantingTaskDefinition("minecraft:overworld", work, 1, NpcPosition(0.0, 64.0, 0.0))
        val state = PlantingTaskState(HarvestResources(emptyMap()))
        val frame = TaskFrame(UUID.randomUUID(), d, planting = state)
        val plot = PlantingPlot(base)
        plot.placed.addAll(SaplingSpecies.DARK_OAK.footprint(base))
        state.plots[base] = plot
        val capture = observed(frame)
        assertEquals(1L, capture.count("completedLayouts").value)
        assertEquals(OperationCountUnit.LAYOUTS, capture.count("completedLayouts").unit)
        assertEquals(4L, capture.count("placedSaplings").value)
        state.plots[base] = PlantingPlot(base, SaplingSpecies.DARK_OAK.footprint(base))
        assertEquals(0L, observed(frame).count("completedLayouts").value)
    }

    @Test fun miningKeepsRemovedResourceCountsSeparateFromUnconfirmedDeliveries() {
        val original = assertIs<MiningTaskDefinition>(definition("mine"))
        val d = original.copy(counting = MiningCounting.REMOVED_RESOURCE_BLOCKS)
        val state = MiningTaskState(ProducedResources.initial(emptyMap()))
        state.selection.removed[NpcBlockPosition(1, 64, 1)] = d.work.resources.values.first()
        val frame = TaskFrame(UUID.randomUUID(), d, mining = state)
        val capture = observed(frame)
        assertEquals(1L, capture.count("removedResourceBlocks").value)
        assertEquals(1L, capture.count("confirmedObjective").value)
        assertEquals(OperationCountUnit.BLOCKS, capture.count("confirmedObjective").unit)
        assertEquals(0L, capture.count("deliveredItems").value)
        state.resources.physical.uncertain = true
        assertTrue(observed(frame).uncertain == true)
        assertTrue(capture.uncertain == false)
    }

    @Test fun exactTargetKillNeedsItsOwnTerminalReceiptAndGuardMissionsDoNotInventKillCounters() {
        val d = assertIs<AttackTaskDefinition>(definition("attack"))
        val frame = TaskFrame(UUID.randomUUID(), d)
        assertNull(observed(frame).counters.find { it.name == "confirmedDefeats" })
        val receipt = TaskCombatOutcome(d.targetUuid, TaskStatus.COMPLETED, TaskReason.TARGET_DEFEATED, 1, "confirmed")
        val done = assertIs<OperationProgress.Observed>(TaskProgressInspections.capture(frame, receipt))
        assertEquals(1L, done.count("confirmedDefeats").value)
        val other = assertIs<OperationProgress.Observed>(TaskProgressInspections.capture(frame, receipt.copy(targetUuid = UUID.randomUUID())))
        assertNull(other.counters.find { it.name == "confirmedDefeats" })
        assertNull(observed(TaskFrame(UUID.randomUUID(), definition("defend"))).counters.find { it.name == "confirmedDefeats" })
    }

    @Test fun deliveryReportsRetainedEvidenceWithUncertaintyRatherThanContainerContents() {
        val d = DeliveryTaskDefinition("minecraft:overworld", NpcBlockPosition(0, 64, 0), "minecraft:stone", 8)
        val resources = ResourceProgress(10, 7, 3, 30, 27, uncertain = true)
        val capture = observed(TaskFrame(UUID.randomUUID(), d, resources = resources))
        assertEquals(3L, capture.count("deliveredItems").value)
        assertEquals(7L, capture.count("confirmedRetainedItems").value)
        assertTrue(capture.uncertain == true)
        assertTrue(capture.reconciliationRequired == true)
        assertNull(capture.counters.find { it.name.contains("container", ignoreCase = true) })
    }

    private fun observed(frame: TaskFrame) = assertIs<OperationProgress.Observed>(TaskProgressInspections.capture(frame))
    private fun OperationProgress.Observed.count(name: String) = counters.single { it.name == name }
    private fun definition(name: String): TaskDefinition {
        val json = checkNotNull(javaClass.getResource("/operation-documents/" + name + ".json")).readText()
        val order = assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(json)).value
        return TaskPublicOrders.definition(order)
    }
}
