package io.samcnpc.behavior.task

import io.samcnpc.behavior.command.TaskTransportCommands
import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TransportLedgerTest {
    private val source = NpcBlockPosition(1, 64, 0)
    private val recipient = NpcBlockPosition(10, 64, 0)
    private val other = NpcBlockPosition(12, 64, 0)
    private val item = "minecraft:oak_log"
    private fun definition() = TransportTaskDefinition("minecraft:overworld", ContainerChoices(listOf(source)), ContainerChoices(listOf(recipient, other)), item, 20, NpcPosition(0.5, 64.0, 0.5))
    private fun record(ledger: TransportLedger = TransportLedger(item, 5)) = TaskRecord.start(UUID(0, 1), definition(), emptyList()).also { it.primary.transport = TransportTaskState(ledger) }
    private fun withdraw(before: Int, amount: Int, at: NpcBlockPosition = source, stock: Int = 100, requested: Int = amount) =
        ContainerTransferObservation(at, item, ContainerTransferDirection.WITHDRAW, requested, before, before + amount, stock, stock - amount, 27, "minecraft:chest")
    private fun deposit(before: Int, amount: Int, at: NpcBlockPosition = recipient, stock: Int = 0, requested: Int = amount) =
        ContainerTransferObservation(at, item, ContainerTransferDirection.DEPOSIT, requested, before, before - amount, stock, stock + amount, 27, "minecraft:chest")

    @Test fun incidentalPickupAndInitialStockCannotBecomeTransportCargo() {
        val ledger = TransportLedger(item, 5)
        assertNull(ledger.observeLive(12))
        assertEquals(12, ledger.protected); assertEquals(0, ledger.deliverable(0)); assertEquals(20, ledger.needed(20, 0))
        assertTrue(ledger.confirm(withdraw(12, 20)))
        assertEquals(20, ledger.deliverable(0)); assertEquals(0, ledger.needed(20, 0))
        assertTrue(ledger.confirm(deposit(32, 20)))
        assertEquals(12, ledger.retained); assertEquals(7, ledger.incidentalGained); assertEquals(20, ledger.delivered)
        assertTrue(ledger.valid())
    }
    @Test fun reservesAndCargoLossDoNotSilentlySpendEarlierInventory() {
        val ledger = TransportLedger(item, 2)
        assertEquals(11, ledger.needed(8, 5))
        assertTrue(ledger.confirm(withdraw(2, 11)))
        assertEquals(8, ledger.deliverable(5))
        assertNull(ledger.observeLive(9))
        assertEquals(4, ledger.cargoLost); assertEquals(2, ledger.protected); assertEquals(4, ledger.deliverable(5))
        assertEquals(4, ledger.needed(8, 5))
        assertTrue(ledger.confirm(withdraw(9, 4)))
        assertTrue(ledger.confirm(deposit(13, 8)))
        assertEquals(5, ledger.retained); assertEquals(3, ledger.cargo); assertEquals(0, ledger.incidentalLost)
        assertTrue(ledger.valid())
    }
    @Test fun physicalPartialAmountsAndSeparateRecipientsSurvivePauseReload() {
        val ledger = TransportLedger(item, 5)
        assertTrue(ledger.confirm(withdraw(5, 20)))
        val first = deposit(25, 6, requested = 20)
        assertTrue(first.partial); assertTrue(ledger.confirm(first))
        val task = record(ledger); task.advanceTime(100); task.pause()
        val restored = TaskCodec.read(TaskCodec.write(task)); val saved = assertNotNull(restored.primary.transport).ledger
        assertEquals(6, saved.delivered); assertEquals(5900, restored.primary.remainingTicks)
        assertEquals(6, saved.deliveries[recipient]); assertEquals(14, saved.cargo)
        assertNull(saved.reconcileLoad(UUID(0, 7), 19))
        // Another actor's intervening chest changes are an observation, never our delivery credit.
        assertTrue(saved.confirm(deposit(19, 14, other, stock = 42)))
        assertEquals(mapOf(recipient to 6, other to 14), saved.deliveries)
        assertEquals(20, saved.delivered); assertEquals(5, saved.retained)
        restored.resume(); restored.completeActive(TaskReason.DELIVERED, "actual cargo quota")
        assertEquals(restored.report(), TaskCodec.read(TaskCodec.write(restored)).report())
    }
    @Test fun mixedBodySaveStopsBeforeLiveChangesCanHideTheMismatch() {
        val ledger = TransportLedger(item, 5); assertTrue(ledger.confirm(withdraw(5, 20))); assertTrue(ledger.confirm(deposit(25, 6)))
        val restored = assertNotNull(TaskCodec.read(TaskCodec.write(record(ledger))).primary.transport).ledger
        assertNotNull(restored.reconcileLoad(UUID(0, 8), 25))
        assertTrue(restored.uncertain); assertEquals(6, restored.delivered); assertEquals(19, restored.retained)
        assertEquals(ledger.lastTransfer, restored.lastTransfer)
    }
    @Test fun unmatchedTransfersForgedSuccessAndFutureStateAreRejected() {
        val ledger = TransportLedger(item, 5)
        assertFalse(ledger.confirm(withdraw(5, 2).copy(containerAfter = 99)))
        assertEquals(0, ledger.withdrawn); assertEquals(5, ledger.retained)
        val valid = TaskCodec.write(record())
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(valid.copy().apply { putString("status", "COMPLETED"); putString("reason", "DELIVERED") }) }
        for (version in 1..4) {
            val file = CompoundTag().apply { putInt("version", version); put("tasks", ListTag().apply { add(valid.copy()) }) }
            val rejected = TaskStore.load(file)
            assertNull(rejected.get(UUID(0, 1))); assertEquals(file, rejected.save(CompoundTag()))
        }
        for (field in listOf("retained", "withdrawn", "delivered", "cargoLost", "initial")) {
            val changed = valid.copy(); changed.getList("frames", 10).getCompound(0).getCompound("transport").getCompound("ledger").putInt(field, 200)
            assertFailsWith<IllegalArgumentException> { TaskCodec.read(changed) }
        }
    }
    @Test fun containerAlternativesAreImmutableBoundedAndNeverBroadenTravelOrSourcePermission() {
        val mutable = mutableListOf(source); val choices = ContainerChoices(mutable); mutable.add(other)
        assertEquals(listOf(source), choices.positions)
        assertNull(definition().validationProblem())
        assertNotNull(definition().copy(destinations = ContainerChoices(listOf(source))).validationProblem())
        assertNotNull(definition().copy(sources = ContainerChoices(listOf(NpcBlockPosition(100, 64, 0)))).validationProblem())
        assertNotNull(definition().copy(travelRadius = Double.NaN).validationProblem())
        for (value in listOf("", "1,2", "1,64,0;1,64,0", "1.5,64,0", (1..9).joinToString(";") { "$it,64,0" })) {
            assertFailsWith<IllegalArgumentException> { TaskTransportCommands.parsePositions(value) }
        }
        assertEquals(listOf(source, recipient), TaskTransportCommands.parsePositions("1,64,0;10,64,0"))
    }
}
