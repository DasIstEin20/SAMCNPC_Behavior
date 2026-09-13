package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class ResourceProgressTest {
    private val definition = DeliveryTaskDefinition("minecraft:overworld", NpcBlockPosition(3, 64, 2), "minecraft:oak_log", 12, 4)
    private fun task(): TaskRecord = TaskRecord.start(UUID(0, 2), definition, listOf("test:prior")).also {
        it.primary.resources = ResourceProgress(16, 16, 0, 58, 27)
    }

    @Test fun aNewBodyLoadCannotHideItsOldStockBehindLaterInventoryChanges() {
        val ledger = ResourceProgress(16, 16, 0, 58, 27)
        val first = UUID(0, 1)
        assertNull(ledger.reconcileLoadedInventory(first, 16))
        assertTrue(ledger.confirm(10, 64, 6))
        assertNull(ledger.reconcileLoadedInventory(first, 16))
        val savedReceipt = ledger.receipt
        assertNotNull(ledger.reconcileLoadedInventory(UUID(0, 2), 16))
        assertTrue(ledger.uncertain)
        assertEquals(10, ledger.retained)
        assertEquals(savedReceipt, ledger.receipt)
    }

    @Test fun loadObservationGenerationNeverSurvivesSavedDataAsAValidationClaim() {
        val record = task()
        val ledger = assertNotNull(record.primary.resources)
        ledger.observedLoadGeneration = UUID(0, 10)
        val encoded = TaskCodec.write(record)
        assertFalse(encoded.toString().contains(ledger.observedLoadGeneration.toString()))
        val restored = assertNotNull(TaskCodec.read(encoded).primary.resources)
        assertNull(restored.observedLoadGeneration)
        assertNull(restored.reconcileLoadedInventory(UUID(0, 20), 16))
        assertFalse(restored.uncertain)
    }

    @Test fun partialEffectsRetainTheirReceiptAndDoNotCountTheRequestedAmount() {
        val record = task()
        val resources = assertNotNull(record.primary.resources)
        assertTrue(resources.confirm(10, 64, 12))
        assertEquals(6, resources.delivered)
        assertEquals(6, resources.receipt?.amount)
        assertTrue(resources.describe(12).contains("shortage=6"))
        record.pause()
        val restored = TaskCodec.read(TaskCodec.write(record))
        val ledger = assertNotNull(restored.primary.resources)
        assertEquals(TaskStatus.PAUSED, restored.status)
        assertEquals(resources.receipt, ledger.receipt)
        assertNull(ledger.reconcile(10, 64, 27))
        restored.resume()
        assertTrue(ledger.confirm(4, 70, 6))
        restored.completeActive(TaskReason.DELIVERED, "confirmed")
        assertTrue(ledger.validate(12))
        assertEquals(TaskStatus.COMPLETED, TaskCodec.read(TaskCodec.write(restored)).status)
    }

    @Test fun mixedSaveSnapshotsNeverRewriteConfirmedDeliveryOrRepairInventory() {
        for ((npc, chest) in listOf(16 to 64, 10 to 58, 9 to 64, 10 to 65)) {
            val ledger = ResourceProgress(16, 16, 0, 58, 27)
            assertTrue(ledger.confirm(10, 64, 6))
            val receipt = ledger.receipt
            assertNotNull(ledger.reconcile(npc, chest, 27))
            assertTrue(ledger.uncertain)
            assertEquals(6, ledger.delivered)
            assertEquals(10, ledger.retained)
            assertEquals(npc, ledger.observedRetained)
            assertEquals(receipt, ledger.receipt)
        }
    }

    @Test fun unmatchedOrOverlargeEffectsCannotBecomeConfirmedProgress() {
        for ((npc, chest, requested) in listOf(10 to 63 to 12, 16 to 58 to 12, 3 to 71 to 12).map { Triple(it.first.first, it.first.second, it.second) }) {
            val ledger = ResourceProgress(16, 16, 0, 58, 27)
            assertFalse(ledger.confirm(npc, chest, requested))
            assertEquals(0, ledger.delivered)
            assertNull(ledger.receipt)
            assertTrue(ledger.uncertain)
        }
    }

    @Test fun malformedLedgersAndInventedSuccessfulDeliveriesAreRejected() {
        val record = task()
        val badLedger = TaskCodec.write(record)
        badLedger.getList("frames", 10).getCompound(0).getCompound("resources").putInt("delivered", 12)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(badLedger) }
        val falseSuccess = TaskCodec.write(record).apply { putString("status", "COMPLETED"); putString("reason", "DELIVERED") }
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(falseSuccess) }
        assertNull(record.interrupt(NavigateTaskDefinition("minecraft:overworld", NpcPosition(2.5, 64.0, 2.5))))
        assertNotNull(record.interrupt(definition))
    }

    @Test fun v1NavigationMigratesWithoutAnyResourceHistoryOrExecutionHandle() {
        val record = TaskRecord.start(UUID(0, 2), NavigateTaskDefinition("minecraft:overworld", NpcPosition(2.5, 64.0, 2.5)), emptyList())
        val root = CompoundTag().apply { putInt("version", 1); put("tasks", ListTag().apply { add(TaskCodec.write(record).apply { remove("reaction") }) }) }
        val store = TaskStore.load(root)
        val migrated = assertNotNull(store.get(record.npcUuid))
        assertEquals(record.id, migrated.id)
        assertNull(migrated.primary.resources)
        assertEquals(9, store.save(CompoundTag()).getInt("version"))
        assertEquals(TaskReactionPolicy(), migrated.reaction.policy)
        assertEquals(0, migrated.reaction.cooldownRemaining)
        assertNull(migrated.lastCombat)
    }

    @Test fun boundsAndResourceDefinitionsCannotBypassTheSharedContract() {
        for (invalid in listOf(definition.copy(quantity = 0), definition.copy(quantity = 2305), definition.copy(keepAtLeast = -1),
            definition.copy(itemId = "minecraft:oak_log; command"), definition.copy(version = 2), definition.copy(budget = TaskBudget(attempts = 9)))) {
            assertNotNull(invalid.validationProblem())
        }
        assertNull(definition.validationProblem())
    }
}
