package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskPublicOrdersTest {
    private val dimension = "minecraft:overworld"
    private val anchor = NpcPosition(0.5, 65.0, 0.5)
    private val recipient = NpcBlockPosition(4, 65, 0)
    private val source = NpcBlockPosition(2, 65, 0)
    private val endpoint = NpcContainerEndpoint(dimension, NpcBlockPosition(8, 65, 0))
    private val feed = OperationMachinePort(endpoint, 0, "minecraft:raw_iron", 4)
    private val output = OperationMachinePort(endpoint, 2, "minecraft:iron_ingot", 4)
    private fun orders(): List<OperationOrder> = listOf(
        OperationOrder.Navigate(dimension, anchor),
        OperationOrder.Deliver(dimension, recipient, "minecraft:iron_ingot", 4, anchor, keepAtLeast = 3),
        OperationOrder.Transport(dimension, OperationContainers(listOf(source)), OperationContainers(listOf(recipient)),
            "minecraft:iron_ingot", 4, anchor, sourceKeepAtLeast = 2, returnTo = anchor),
        OperationOrder.Machine(dimension, OperationMachineFeeds(listOf(feed)), output, anchor, returnTo = anchor),
        OperationOrder.Fish(dimension, NpcBlockPosition(4, 64, 0), anchor, 2, anchor),
        OperationOrder.Explore(dimension, anchor),
        OperationCombatOrder.Attack(dimension, UUID(2, 3), anchor),
        OperationCombatOrder.Defend(dimension, anchor, 16.0, subjectUuid = UUID(2, 3)),
        OperationCombatOrder.AreaAttack(dimension, anchor, 16.0, NpcEntityTypeFilter.of(setOf("minecraft:husk")), 1),
        OperationCombatOrder.Patrol(dimension, anchor, 16.0, listOf(anchor)),
        OperationInventoryOrder(dimension, OperationInventoryWork.Pickup(listOf("minecraft:oak_log")), anchor),
        OperationPrepareFieldOrder(dimension,OperationWorkArea(OperationWorkBox(NpcBlockPosition(4,64,0),NpcBlockPosition(6,64,2))),anchor,returnTo=anchor),
    ) + TaskPublicHarvestOrdersTest.examples()

    @Test fun everySupportedOrderUsesEstablishedDefinitionAndSaveRoundTrip() {
        val samples = orders()
        assertEquals(OperationType.entries.toSet(), samples.map { it.type }.toSet())
        for (order in samples) {
            assertEquals(NpcActionStatus.SUCCEEDED, OperationSupervisionApi.validateOrder(order).status)
            val definition = TaskPublicOrders.definition(order)
            assertEquals(order.type.operationId, definition.operationId)
            assertEquals(order.type.definitionVersion, definition.version)
            assertEquals(TaskBudget(order.budget.ticks, order.budget.attempts, order.budget.backoffTicks), definition.budget)
            val original = TaskCodec.writeDefinition(definition)
            assertEquals(original, TaskCodec.writeDefinition(TaskCodec.readDefinition(original)))
        }
        val delivery = TaskPublicOrders.definition(samples[1]) as DeliveryTaskDefinition
        assertEquals(2, delivery.version); assertEquals(anchor, delivery.anchor); assertEquals(3, delivery.keepAtLeast)
        val cargo = TaskPublicOrders.definition(samples[2]) as TransportTaskDefinition
        assertEquals(2, cargo.sourceKeepAtLeast); assertEquals(anchor, cargo.returnTo)
    }

    @Test fun sharedValidatorsRejectUnsafeBoundsAndRelatedFieldsBeforeWorldAccess() {
        val samples = orders()
        val bad = listOf(
            (samples[0] as OperationOrder.Navigate).copy(destination = anchor.copy(x = Double.NaN)),
            (samples[0] as OperationOrder.Navigate).copy(budget = OperationBudget(ticks = 72001)),
            (samples[1] as OperationOrder.Deliver).copy(quantity = 0),
            (samples[1] as OperationOrder.Deliver).copy(itemId = "not a registered identifier"),
            (samples[1] as OperationOrder.Deliver).copy(anchor = anchor.copy(x = 100.0)),
            (samples[2] as OperationOrder.Transport).copy(sources = OperationContainers(listOf(recipient))),
            (samples[2] as OperationOrder.Transport).copy(sourceKeepAtLeast = 2305),
            (samples[3] as OperationOrder.Machine).copy(pollTicks = 4),
            (samples[3] as OperationOrder.Machine).copy(noProgressTicks = 10, pollTicks = 20),
            (samples[3] as OperationOrder.Machine).copy(output = output.copy(itemId = feed.itemId)),
            (samples[4] as OperationOrder.Fish).copy(catches = 65),
            (samples[4] as OperationOrder.Fish).copy(pickupWaitTicks = 240, budget = OperationBudget(ticks = 200)),
            (samples[4] as OperationOrder.Fish).copy(water = NpcBlockPosition(20, 64, 0)),
            (samples[5] as OperationOrder.Explore).copy(chunkBudget = 9),
            (samples[5] as OperationOrder.Explore).copy(radius = 97),
            (samples[5] as OperationOrder.Explore).copy(heading = 4),
        )
        for (order in bad) {
            val result = OperationSupervisionApi.validateOrder(order)
            assertEquals(NpcActionStatus.REJECTED, result.status, order.toString())
            assertTrue(result.detail.isNotBlank())
        }
        assertEquals(NpcActionStatus.SUCCEEDED, OperationSupervisionApi.validateOrder(
            OperationOrder.Navigate(dimension, anchor, budget = OperationBudget(20, 1, 1))).status)
        assertEquals(NpcActionStatus.SUCCEEDED, OperationSupervisionApi.validateOrder(
            OperationOrder.Navigate(dimension, anchor, budget = OperationBudget(72000, 8, 200))).status)
    }

    @Test fun priorTaskCompareAndSetCannotReplayAfterCompletionOrReplacement() {
        val order = orders().first()
        val request = OperationAssignmentRequest(null, 100, 200, order)
        assertNull(TaskAssignmentGuard.rejection(null, request, 100))
        assertNull(TaskAssignmentGuard.rejection(null, request.copy(issuedTick = Long.MAX_VALUE - 1, expiresTick = Long.MAX_VALUE), Long.MAX_VALUE - 1))
        for (bad in listOf(request.copy(issuedTick = -1), request.copy(expiresTick = 100),
            request.copy(expiresTick = 1301), request.copy(issuedTick = 101))) {
            assertNotNull(TaskAssignmentGuard.rejection(null, bad, 100))
        }
        assertNotNull(TaskAssignmentGuard.rejection(null, request, 200))
        val record = TaskRecord.start(UUID.randomUUID(), TaskPublicOrders.definition(order), emptyList())
        val bytes = TaskCodec.write(record)
        assertEquals(NpcActionCode.CONFLICT, TaskAssignmentGuard.rejection(record, request, 100)?.code)
        assertEquals(NpcActionCode.CONFLICT, TaskAssignmentGuard.rejection(record, request.copy(expectedPriorTaskId = record.id), 100)?.code)
        assertEquals(bytes, TaskCodec.write(record))
        record.finish(TaskStatus.COMPLETED, TaskReason.ARRIVED, "physical task finished")
        val loaded = TaskCodec.read(TaskCodec.write(record))
        assertEquals(NpcActionCode.CONFLICT, TaskAssignmentGuard.rejection(loaded, request, 100)?.code)
        assertNull(TaskAssignmentGuard.rejection(loaded, request.copy(expectedPriorTaskId = record.id), 100))
        val next = TaskRecord.start(record.npcUuid, TaskPublicOrders.definition(order), emptyList())
        assertEquals(NpcActionCode.CONFLICT, TaskAssignmentGuard.rejection(next, request.copy(expectedPriorTaskId = record.id), 100)?.code)
    }

    @Test fun machineInputsAreBoundedCopiedValuesAcrossAnIntegrationHandoff() {
        val mutable = mutableListOf(feed)
        val feeds = OperationMachineFeeds(mutable)
        mutable.clear()
        assertEquals(listOf(feed), feeds.ports)
        assertFailsWith<UnsupportedOperationException> { (feeds.ports as MutableList).clear() }
        assertFailsWith<IllegalArgumentException> { OperationMachineFeeds(emptyList()) }
        assertFailsWith<IllegalArgumentException> { OperationMachineFeeds(List(5) { feed }) }
        val duplicate = OperationOrder.Machine(dimension, OperationMachineFeeds(listOf(feed, feed)), output, anchor)
        assertEquals(NpcActionStatus.REJECTED, OperationSupervisionApi.validateOrder(duplicate).status)
    }
}
