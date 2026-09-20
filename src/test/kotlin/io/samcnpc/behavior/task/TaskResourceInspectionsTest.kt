package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.kernel.inventory.ContainerTransferDirection
import io.samcnpc.behavior.kernel.inventory.ContainerTransferObservation
import io.samcnpc.core.api.NpcBlockPosition
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class TaskResourceInspectionsTest {
    @Test fun allOperationVariantsDistinguishAbsentAccountingFromNotYetInitialized() {
        val directory = Path.of(checkNotNull(javaClass.getResource("/operation-documents")).toURI())
        val paths = Files.walk(directory).use { stream -> stream.filter { it.toString().endsWith(".json") }.toList() }
        assertEquals(46, paths.size)
        for (path in paths) {
            val order = assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(Files.readString(path))).value
            val definition = TaskPublicOrders.definition(order)
            val value = TaskResourceInspections.capture(TaskFrame(UUID.randomUUID(), definition))
            if (definition is NavigateTaskDefinition || definition is ExplorerTaskDefinition ||
                definition is AttackTaskDefinition || definition is CombatMissionDefinition) {
                assertSame(OperationResourceInspection.NotTracked, value, path.toString())
            } else assertSame(OperationResourceInspection.NotInitialized, value, path.toString())
        }
    }

    @Test fun physicalCountersAreDetachedAndReadingDoesNotReconcileTheLedger() {
        val ledger = HarvestResources(mapOf("minecraft:cod" to 2))
        assertNull(ledger.observeLive(mapOf("minecraft:cod" to 5)))
        val frame = frame("fish")
        frame.fishing = FishingTaskState(ledger)
        val state = checkpoint(frame)
        assertEquals(OperationResourceLedgerKind.PHYSICAL, state.kind)
        assertTrue(state.reconciliationRequired)
        assertEquals(mapOf("initial" to 2L, "gathered" to 3L, "supplied" to 0L, "consumed" to 0L,
            "delivered" to 0L, "lost" to 0L, "retained" to 5L), counts(state))
        assertTrue(ledger.mustReconcileLoad)
        assertNull(ledger.observeLive(mapOf("minecraft:cod" to 1)))
        assertEquals(5L, counts(state)["retained"])
        assertFailsWith<UnsupportedOperationException> { (state.items as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (state.items.single().counters as MutableList).clear() }
    }

    @Test fun producedFoodSeparatesPrivateStockFromOutputAndRetainsUncertainty() {
        val item = "minecraft:carrot"
        val ledger = ProducedResources.initial(mapOf(item to 3))
        assertNull(ledger.observeLive(mapOf(item to 11)))
        assertNull(ledger.consume(item, 2, mapOf(item to 9)))
        ledger.physical.uncertain = true
        val frame = frame("food")
        frame.food = FoodTaskState(ledger)
        val state = checkpoint(frame)
        assertEquals(OperationResourceLedgerKind.PRODUCED, state.kind)
        assertEquals(1L, counts(state)["stock"])
        assertEquals(8L, counts(state)["output"])
        assertEquals(9L, counts(state)["retained"])
        assertEquals(0L, counts(state)["deliveredOutput"])
        assertTrue(state.uncertain && state.reconciliationRequired)
    }

    @Test fun transportKeepsIncidentalStockProtectedAndNeverExportsContainerCounts() {
        val item = "minecraft:stone"
        val ledger = TransportLedger(item, 10)
        val source = NpcBlockPosition(1, 64, 0)
        assertTrue(ledger.confirm(ContainerTransferObservation(source, item, ContainerTransferDirection.WITHDRAW,
            8, 10, 18, 31, 23, 27, "minecraft:chest")))
        assertNull(ledger.observeLive(20))
        assertTrue(ledger.confirm(ContainerTransferObservation(source, item, ContainerTransferDirection.DEPOSIT,
            3, 20, 17, 773, 776, 27, "minecraft:chest")))
        val frame = frame("transport")
        frame.transport = TransportTaskState(ledger)
        val state = checkpoint(frame)
        assertEquals(5L, counts(state)["cargo"])
        assertEquals(12L, counts(state)["protectedStock"])
        assertEquals(3L, counts(state)["delivered"])
        assertFalse(counts(state).keys.any { it.contains("container", ignoreCase = true) })
        assertFalse(counts(state).values.any { it == 773L || it == 776L })
        val legacy = TaskFrame(UUID.randomUUID(), DeliveryTaskDefinition("minecraft:overworld", source, item, 10))
        legacy.resources = ResourceProgress(10, 7, 3, 776, 27, TransferReceipt(1, 10, 7, 773, 776))
        assertEquals(mapOf("initial" to 10L, "confirmedRetained" to 7L, "delivered" to 3L), counts(checkpoint(legacy)))
    }

    private fun checkpoint(frame: TaskFrame) = assertIs<OperationResourceInspection.Checkpoint>(TaskResourceInspections.capture(frame))
    private fun counts(value: OperationResourceInspection.Checkpoint) = value.items.single().counters.associate { it.name to it.value }
    private fun frame(name: String): TaskFrame {
        val document = checkNotNull(javaClass.getResource("/operation-documents/" + name + ".json")).readText()
        val order = assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(document)).value
        return TaskFrame(UUID.randomUUID(), TaskPublicOrders.definition(order))
    }
}
