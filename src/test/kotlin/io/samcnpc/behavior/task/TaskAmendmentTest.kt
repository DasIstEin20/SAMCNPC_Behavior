package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatPolicyWorld
import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskAmendmentTest {
    private val source = NpcBlockPosition(2, 64, 0)
    private val first = NpcBlockPosition(8, 64, 0)
    private val second = NpcBlockPosition(12, 64, 0)
    private val item = "minecraft:oak_log"
    private val actor = UUID(0, 2)
    private var requestSequence = 100L
    private fun request(record: TaskRecord, change: TaskChange) = TaskAmendmentRequest(record.id, UUID(0, requestSequence++), actor, record.amendments.revision, 100, 1300, change)
    private fun definition() = TransportTaskDefinition("minecraft:overworld", ContainerChoices(listOf(source)), ContainerChoices(listOf(first)), item, 64, NpcPosition(0.0, 64.0, 0.0))
    private fun transport(): TaskRecord {
        val ledger = TransportLedger(item, 5)
        assertTrue(ledger.confirm(ContainerTransferObservation(source, item, ContainerTransferDirection.WITHDRAW, 64, 5, 69, 100, 36, 27, "minecraft:chest")))
        assertTrue(ledger.confirm(ContainerTransferObservation(first, item, ContainerTransferDirection.DEPOSIT, 40, 69, 29, 0, 40, 27, "minecraft:chest")))
        ledger.mustReconcileLoad = false
        return TaskRecord.start(UUID(0, 1), definition(), emptyList()).also {
            it.primary.transport = TransportTaskState(ledger, TransportPhase.DESTINATION)
            it.advanceTime(200); it.primary.failures = 1; it.totalFailures = 1; it.pause()
        }
    }
    @Test fun additionRedirectAndShrinkingPreserveFactsIdentityPauseAndElapsedBudgets() {
        val old = transport(); val fixture = Fixture(29)
        val change = request(old, TaskChange.Quantity(32, QuantityChangeMode.ADD))
        val increased = TaskAmendmentPreparation.prepare(old, change, fixture.body, fixture.world)
        assertEquals(64, (old.primary.definition as TransportTaskDefinition).quantity)
        assertEquals(0, old.amendments.revision)
        assertEquals(96, (increased.primary.definition as TransportTaskDefinition).quantity)
        assertEquals(40, increased.primary.transport?.ledger?.delivered); assertEquals(5800, increased.primary.remainingTicks)
        assertEquals(old.id, increased.id); assertEquals(old.primary.id, increased.primary.id); assertEquals(TaskStatus.PAUSED, increased.status)
        assertEquals(1, increased.totalFailures); assertEquals(1, increased.primary.failures)
        assertTrue(checkNotNull(increased.amendments.replay(change)).startsWith("APPLIED"))
        val restored = TaskCodec.read(TaskCodec.write(increased))
        assertEquals(increased.amendments.replay(change), restored.amendments.replay(change))
        assertTrue(checkNotNull(restored.amendments.replay(change.copy(change = TaskChange.Quantity(33, QuantityChangeMode.ADD)))).startsWith("CONFLICT"))
        val redirected = TaskAmendmentPreparation.prepare(increased, request(increased, TaskChange.Redirect(ContainerChoices(listOf(second)))), fixture.body, fixture.world)
        val state = assertNotNull(redirected.primary.transport)
        assertEquals(mapOf(first to 40), state.ledger.deliveries); assertEquals(24, state.ledger.cargo)
        assertNull(state.selected); assertEquals(2, redirected.amendments.revision)
        val reduced = TaskAmendmentPreparation.prepare(redirected, request(redirected, TaskChange.Quantity(20, QuantityChangeMode.TOTAL)), fixture.body, fixture.world)
        assertTrue(checkNotNull(reduced.primary.transport).ledger.describe(20).contains("excess=20"))
        assertEquals(40, TaskCodec.read(TaskCodec.write(reduced)).primary.transport?.ledger?.delivered)
        assertEquals(0, fixture.worldActions)
    }
    @Test fun newResourceGetsNewCounterAndArchivedRecipientCreditWithoutConvertingOldCargo() {
        val old = transport(); val fixture = Fixture(29)
        val replacement = definition().copy(itemId = "minecraft:birch_log", quantity = 12)
        assertFailsWith<IllegalArgumentException> { TaskAmendmentPreparation.prepare(old, request(old, TaskChange.Replace(replacement)), fixture.body, fixture.world) }
        val change = request(old, TaskChange.Replace(replacement, ObjectiveChangeMode.NEW_OBJECTIVE))
        val changed = TaskAmendmentPreparation.prepare(old, change, fixture.body, fixture.world)
        assertEquals(change.requestId, changed.amendments.objectiveId)
        assertEquals(0, changed.primary.transport?.ledger?.delivered); assertEquals(0, changed.primary.transport?.ledger?.initial)
        val archived = changed.amendments.objectives.single()
        assertEquals(40, archived.confirmed); assertEquals(40, archived.deliveries[first]?.get(item)); assertEquals(29, archived.resources[item]?.retained)
        assertEquals(64, archived.resources[item]?.supplied); assertEquals(5800, changed.primary.remainingTicks)
        val loaded = TaskCodec.read(TaskCodec.write(changed))
        assertEquals(archived, loaded.amendments.objectives.single()); assertEquals(0, fixture.worldActions)
        assertEquals(40, old.primary.transport?.ledger?.delivered)
    }
    @Test fun publishedCarriedDeliveryMigratesOriginalReceiptAndOnlySendsRemainingCreditElsewhere() {
        val definition = DeliveryTaskDefinition("minecraft:overworld", first, item, 64)
        val old = TaskRecord.start(UUID(0, 1), definition, emptyList())
        old.primary.resources = ResourceProgress(80, 40, 40, 40, 27, TransferReceipt(1, 80, 40, 0, 40))
        old.advanceTime(50)
        val fixture = Fixture(40)
        val changed = TaskAmendmentPreparation.prepare(old, request(old, TaskChange.Redirect(ContainerChoices(listOf(second)))), fixture.body, fixture.world)
        assertNull(changed.primary.resources); assertEquals(2, changed.primary.definition.version)
        val ledger = assertNotNull(changed.primary.transport).ledger
        assertEquals(80, ledger.initialCargo); assertEquals(40, ledger.cargo); assertEquals(40, ledger.delivered)
        assertEquals(old.primary.resources?.receipt, ledger.legacyCredit?.receipt); assertNull(ledger.lastTransfer)
        assertEquals(mapOf(first to 40), ledger.deliveries); assertTrue(ledger.valid())
        val saved = TaskCodec.read(TaskCodec.write(changed))
        assertEquals(ledger.legacyCredit, saved.primary.transport?.ledger?.legacyCredit)
        assertEquals(5950, saved.primary.remainingTicks); assertEquals(0, fixture.worldActions)
    }
    @Test fun failedFreshObservationIsAtomicAndCannotRefreshLostCargoOrDefinition() {
        val old = transport(); val fixture = Fixture(30)
        val original = TaskCodec.write(old)
        fixture.unavailable = second
        assertFailsWith<IllegalArgumentException> { TaskAmendmentPreparation.prepare(old, request(old, TaskChange.Redirect(ContainerChoices(listOf(second)))), fixture.body, fixture.world) }
        assertEquals(original, TaskCodec.write(old)); assertEquals(0, old.primary.transport?.ledger?.incidentalGained)
        assertEquals(0, fixture.worldActions)
    }
    @Test fun explicitExtensionRetainsSpentTimeAndBoundedHistoryRejectsForgery() {
        val old = transport(); val fixture = Fixture(29)
        val updated = TaskAmendmentPreparation.prepare(old, request(old, TaskChange.ExtendTime(400)), fixture.body, fixture.world)
        assertEquals(6400, updated.primary.definition.budget.ticks); assertEquals(6200, updated.primary.remainingTicks)
        assertFailsWith<IllegalArgumentException> { TaskAmendmentPreparation.prepare(old, request(old, TaskChange.Replace(definition().copy(budget = TaskBudget(ticks = 7000)))), fixture.body, fixture.world) }
        val saved = TaskCodec.write(updated)
        val revision = saved.copy(); revision.getCompound("amendments").putInt("revision", 20)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(revision) }
        val duplicate = saved.copy(); val list = duplicate.getCompound("amendments").getList("receipts", 10); list.add(list.getCompound(0).copy())
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(duplicate) }
        for (version in 1..4) assertFailsWith<IllegalArgumentException> { TaskCodec.read(saved, version) }
        assertNotNull(request(old, TaskChange.ExtendTime(1)).copy(expiresTick = 1301).validationProblem())
    }
    @Test fun pendingExpiryAndCancellationKeepTheirReplayIdsAndOriginalRevisionAfterReload() {
        val old = transport(); val change = request(old, TaskChange.Redirect(ContainerChoices(listOf(second))))
        old.amendments.pending = change
        val loaded = TaskCodec.read(TaskCodec.write(old))
        assertTrue(checkNotNull(loaded.amendments.replay(change)).startsWith("PENDING"))
        loaded.finish(TaskStatus.CANCELLED, TaskReason.USER_CANCELLED, "cancel test")
        assertNull(loaded.amendments.pending); assertEquals(0, loaded.amendments.revision)
        val final = TaskCodec.read(TaskCodec.write(loaded))
        assertTrue(checkNotNull(final.amendments.replay(change)).startsWith("REJECTED"))
        assertEquals(40, final.primary.transport?.ledger?.delivered)
    }
    private inner class Fixture(var carried: Int) {
        var worldActions = 0
        var unavailable: NpcBlockPosition? = null
        private fun stack(id: String, count: Int) = NpcItemStackSnapshot(if (count == 0) null else id, count, 64, 0, 0)
        val world = object : NpcWorldView by CombatPolicyWorld() {
            override fun observeBlock(position: NpcBlockPosition) = if (position == unavailable) null else NpcBlockObservation(position, "minecraft:chest", false, true, true)
            override fun observeBlockContainer(position: NpcBlockPosition): NpcBlockContainerObservation? {
                if (position == unavailable) return null
                val count = if (position == first) 40 else if (position == source) 36 else 0
                return NpcBlockContainerObservation(position, 27, (0 until 27).map { slot -> NpcBlockContainerSlotObservation(slot, stack(item, if (slot == 0) count else 0), if (slot == 0 && count > 0) NpcItemKnowledge(item, emptySet()) else NpcItemKnowledge.EMPTY) })
            }
        }
        val body = object : TestNpcFacade() {
            override fun snapshot() = decisionContext(tick = 100).snapshot
            override fun worldView() = world
            override fun inventoryContents() = listOf(NpcInventoryEntry(0, stack(item, carried), NpcItemKnowledge(item, emptySet())))
            override fun stopControl(): NpcActionResult { worldActions++; return NpcActionResult.succeeded("controlled stop") }
        }
    }
}
