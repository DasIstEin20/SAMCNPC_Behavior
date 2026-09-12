package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatPolicyWorld
import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class InventoryWorkTest {
    private val item = "minecraft:cobblestone"
    private val start = NpcPosition(0.0, 64.0, 0.0)
    private val container = NpcBlockPosition(4, 64, 0)
    private fun choices() = ContainerChoices(listOf(container))
    private fun supply() = SupplyStock(listOf(StockNeed(item, 4, 16, 2)), choices())
    private fun definition(work: InventoryWork = supply(), ticks: Int = 1200) = InventoryTaskDefinition("minecraft:overworld", work, start,
        workTicks = minOf(600, ticks / 2), budget = TaskBudget(ticks))
    private fun parent() = TaskRecord.start(UUID(0, 1), NavigateTaskDefinition("minecraft:overworld", NpcPosition(8.0, 64.0, 0.0), budget = TaskBudget(2000)), emptyList())
    private fun interrupt(record: TaskRecord, fixture: Fixture, definition: InventoryTaskDefinition = definition()) {
        val state = InventoryTaskCapture.capture(fixture, definition, record.amendments.revision, record)
        assertNull(record.interrupt(definition)); record.active.inventory = state
    }
    @Test fun hysteresisIsImmutableAndRejectsContradictoryReserves() {
        val needs = mutableListOf(StockNeed(item, 4, 16))
        val supply = SupplyStock(needs, choices()); needs.clear()
        assertEquals(1, supply.needs.size)
        assertNotNull(TaskLogisticsPolicy(start, supply, UnloadExcess(listOf(ItemReserve(item, 15)), choices())).validationProblem())
        assertNull(TaskLogisticsPolicy(start, supply, UnloadExcess(listOf(ItemReserve(item, 16)), choices())).validationProblem())
        assertNotNull(definition().copy(travelRadius = Double.NaN).validationProblem())
        assertNotNull(TaskLogisticsPolicy(travelRadius = Double.NaN).validationProblem())
        assertNotNull(SupplyStock(listOf(StockNeed(item, 4, 3)), choices()).validationProblem())
        val encoded = InventoryWorkCodec.write(supply)
        assertEquals(encoded, InventoryWorkCodec.write(InventoryWorkCodec.read(encoded)))
        encoded.putString("code", "forbidden")
        assertFailsWith<IllegalArgumentException> { InventoryWorkCodec.read(encoded) }
    }
    @Test fun supplyTriggersBelowMinimumAndCapturesAQuotaWithoutCountingEquipmentTwice() {
        val body = Fixture(); body.set(1, item, 4)
        assertTrue(InventoryTaskCapture.capture(body, definition(), 0).goals.isEmpty())
        body.set(1, item, 3)
        assertEquals(mapOf(item to 13), InventoryTaskCapture.capture(body, definition(), 0).goals)
        body.offHand = stack(item, 1)
        assertTrue(InventoryTaskCapture.capture(body, definition(), 0).goals.isEmpty())
        body.offHand = NpcItemStackSnapshot.EMPTY; body.set(0, item, 2)
        assertEquals(5, HarvestResources.inventoryCounts(body)[item])
    }
    @Test fun unloadProtectsTheSelectedHandEquipmentReserveAndAllPrimaryCargo() {
        val body = Fixture(); body.set(0, item, 5); body.set(1, item, 20); body.offHand = stack(item, 3)
        assertEquals(18, InventoryTaskCapture.unloadable(body, item, 10))
        val work = UnloadExcess(listOf(ItemReserve(item, 10)), choices())
        assertEquals(mapOf(item to 18), InventoryTaskCapture.capture(body, definition(work), 0).goals)
        val parent = TaskRecord.start(body.npcUuid, TransportTaskDefinition("minecraft:overworld", choices(), ContainerChoices(listOf(NpcBlockPosition(8,64,0))), item, 20, start), emptyList())
        assertTrue(InventoryTaskCapture.capture(body, definition(work), 0, parent).goals.isEmpty())
    }
    @Test fun interruptionPauseLoadAndTimeoutRetainThePrimaryDeadlineAndAttempts() {
        val body = Fixture(); val record = parent(); val task = record.id; val primary = record.primary.id
        record.advanceTime(50); record.primary.failures = 1; record.totalFailures = 1
        interrupt(record, body, definition(ticks = 80)); record.advanceTime(20); record.pause()
        val saved = TaskCodec.write(record)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(saved, 4) }
        val loaded = TaskCodec.read(saved); loaded.advanceTime(1000)
        assertEquals(1930, loaded.primary.remainingTicks); assertEquals(60, loaded.active.remainingTicks)
        loaded.resume(); TaskResumeObservation.prime(loaded, body); loaded.advanceTime(60)
        assertEquals(task, loaded.id); assertEquals(primary, loaded.active.id); assertEquals(1870, loaded.primary.remainingTicks)
        assertEquals(1, loaded.primary.failures); assertEquals(1, loaded.totalFailures); assertEquals(1, loaded.completedInterruptions)
        val outcome = loaded.logistics.outcomes.single()
        assertEquals(InventoryWorkReason.TIME_LIMIT, outcome.reason); assertFalse(outcome.returned); assertTrue(outcome.supplied.isEmpty())
        assertEquals(TaskStatus.RUNNING, TaskCodec.read(TaskCodec.write(loaded)).status)
    }
    @Test fun expiredInventoryBeneathCombatUnwindsAfterCombatWithoutRenewingTime() {
        val body = Fixture(); val record = parent(); interrupt(record, body, definition(ticks = 40))
        assertNull(record.interrupt(AttackTaskDefinition("minecraft:overworld", UUID(0,99), start, budget = TaskBudget(100))))
        record.advanceTime(50)
        assertEquals(0, record.frames[1].remainingTicks)
        val restored = TaskCodec.read(TaskCodec.write(record))
        restored.endCombat(TaskStatus.CANCELLED, TaskReason.TARGET_UNAVAILABLE, 0, "gone")
        assertEquals(1, restored.frames.size); assertEquals(2, restored.completedInterruptions)
        assertEquals(1950, restored.primary.remainingTicks); assertEquals(InventoryWorkReason.TIME_LIMIT, restored.logistics.outcomes.single().reason)
        assertEquals(TaskStatus.RUNNING, TaskCodec.read(TaskCodec.write(restored)).status)
    }
    @Test fun auxiliaryCargoSupplyRemainsProtectedAndCannotBecomeAuthorizedTransport() {
        val body = Fixture(); body.set(1, item, 5)
        val definition = TransportTaskDefinition("minecraft:overworld", choices(), ContainerChoices(listOf(NpcBlockPosition(8,64,0))), item, 20, start)
        val parent = TaskRecord.start(body.npcUuid, definition, emptyList()); parent.primary.transport = TaskTransport.capture(body, definition)
        val supply = SupplyStock(listOf(StockNeed(item, 6, 8)), choices())
        interrupt(parent, body, definition(supply)); val state = checkNotNull(parent.active.inventory)
        body.set(1, item, 8)
        val observation = ContainerTransferObservation(container, item, ContainerTransferDirection.WITHDRAW, 3, 5, 8, 20, 17, 27, "minecraft:chest")
        assertNull(state.confirmTransfer(observation, HarvestResources.inventoryCounts(body)))
        assertNull(InventoryParentAccounting.confirm(parent, body, observation))
        val ledger = checkNotNull(parent.primary.transport).ledger
        assertEquals(0, ledger.withdrawn); assertEquals(0, ledger.cargo); assertEquals(8, ledger.retained); assertEquals(3, ledger.incidentalGained)
        state.returning(InventoryWorkReason.SATISFIED, "3 supplied"); parent.endInventory(true, message = state.detail)
        assertEquals(mapOf(item to 3), parent.logistics.outcomes.single().supplied)
        assertEquals(mapOf(container to mapOf(item to 3)), parent.logistics.outcomes.single().sources)
        assertTrue(TaskHistory.inventory(parent,1).contains("transferred=3"))
        assertEquals(0, TaskCodec.read(TaskCodec.write(parent)).primary.transport?.ledger?.cargo)
    }
    @Test fun structuralAmendmentsWaitForReturnButExplicitTimeDoesNotRewriteActiveStepRevision() {
        val body = Fixture(); val record = parent(); interrupt(record, body)
        assertFalse(TaskAmendmentPreparation.boundary(record, TaskChange.Replace(record.primary.definition)))
        assertFalse(TaskAmendmentPreparation.boundary(record, TaskChange.Logistics(TaskLogisticsPolicy())))
        assertTrue(TaskAmendmentPreparation.boundary(record, TaskChange.ExtendTime(100)))
        val change = TaskAmendmentRequest(record.id, UUID(0,44), UUID(0,2), 0, 100, 1300, TaskChange.ExtendTime(100))
        val revised = TaskAmendmentPreparation.prepare(record, change, body, body.world)
        assertEquals(1, revised.amendments.revision); assertEquals(0, revised.active.inventory?.revision)
        assertEquals(2100, revised.primary.remainingTicks); assertEquals(1200, revised.active.remainingTicks)
    }
    @Test fun pickupSkipsOverBudgetStacksAndForeignItemsBeforeAnyAction() {
        val body = Fixture(); val record = parent(); val world = body.world
        fun drop(id: Long, count: Int, itemId: String = item, x: Double = 2.0) = NpcEntityObservation(UUID(0,id), "minecraft:item", NpcPosition(x,64.0,0.0), NpcVector(0.0,0.0,0.0), true, false, null, stack(itemId,count))
        for (drop in listOf(drop(11,8), drop(12,4), drop(13,1,"minecraft:diamond"), drop(14,1,x=10.0))) world.entities[drop.uuid] = drop
        val chosen = InventoryPickupWork.candidates(record, body, world, PickupNearby(listOf(item),4.0,4), start, 4)
        assertEquals(listOf(UUID(0,12)), chosen.map { it.uuid })
    }
    @Test fun forgedCreditAndUnexpectedStateFieldsFailClosed() {
        val body = Fixture(); val definition = definition(); val record = TaskRecord.start(body.npcUuid, definition, emptyList())
        record.primary.inventory = InventoryTaskCapture.capture(body, definition, 0)
        val encoded = InventoryStateCodec.write(checkNotNull(record.primary.inventory))
        encoded.putString("executor", "arbitrary.class")
        assertFailsWith<IllegalArgumentException> { InventoryStateCodec.read(encoded, definition) }
        val tag = TaskCodec.write(record)
        tag.getList("frames", 10).getCompound(0).getCompound("inventory").put("goals", LumberjackTaskCodec.counts(mapOf("minecraft:diamond" to 1)))
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(tag) }
    }
    @Test fun aSatisfiedLabelCannotForgeStockOrUnloadCompletion() {
        val body = Fixture(); val d = definition(); val record = TaskRecord.start(body.npcUuid,d,emptyList())
        val state = InventoryTaskCapture.capture(body,d,0); record.primary.inventory = state
        state.returning(InventoryWorkReason.SATISFIED,"forged label")
        record.reconciledPosition = start
        record.endInventory(true,message=state.detail)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(record)) }
    }
    @Test fun incidentalPickupLeavesActiveNeighborHarvestDropsUntouchedUntilTheLeaseExpires() {
        val claims = io.samcnpc.behavior.kernel.work.SpatialWorkClaimKernel(leaseTicks=10)
        val worker = UUID(0,10); val workerTask = UUID(0,11)
        claims.renewOrClaim(worker,"minecraft:overworld",NpcBlockPosition(2,64,0),0,workerTask)
        claims.renewOrClaim(worker,"minecraft:overworld",NpcBlockPosition(2,64,0),1,workerTask)
        val filter = claims.incidentalCollectionFilter(UUID(0,20),UUID(0,21),"minecraft:overworld",start,4.0,1)
        assertFalse(filter(NpcPosition(3.0,64.0,0.0)))
        assertTrue(filter(NpcPosition(20.0,64.0,0.0)))
        assertTrue(claims.incidentalCollectionFilter(UUID(0,20),UUID(0,21),"minecraft:overworld",start,4.0,11)(NpcPosition(3.0,64.0,0.0)))
        assertTrue(claims.incidentalCollectionFilter(worker,workerTask,"minecraft:overworld",start,4.0,1)(NpcPosition(3.0,64.0,0.0)))
    }
    private inner class Fixture : TestNpcFacade() {
        val world = CombatPolicyWorld()
        private val slots = mutableMapOf<Int, NpcItemStackSnapshot>()
        var offHand = NpcItemStackSnapshot.EMPTY
        override fun snapshot() = decisionContext().snapshot
        override fun inventoryContents() = (0 until 36).map { NpcInventoryEntry(it, slots[it] ?: NpcItemStackSnapshot.EMPTY) }
        override fun equipmentContents() = NpcEquipmentSnapshot(slots[0] ?: NpcItemStackSnapshot.EMPTY, offHand,
            NpcItemStackSnapshot.EMPTY, NpcItemStackSnapshot.EMPTY, NpcItemStackSnapshot.EMPTY, NpcItemStackSnapshot.EMPTY)
        override fun worldView() = world
        fun set(slot: Int, id: String, count: Int) { slots[slot] = stack(id, count) }
    }
    private fun stack(id: String, count: Int) = NpcItemStackSnapshot(id, count, 64, 0, 0)
}
