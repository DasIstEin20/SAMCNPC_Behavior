package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskPublicAmendmentsTest {
    @Test fun publicChangesPreserveValuesAndBoundCopiedContainerChoices() {
        val a = NpcBlockPosition(1, 64, 2); val b = NpcBlockPosition(4, 64, 2)
        val mutable = mutableListOf(a, b)
        val input = OperationContainers(mutable, OperationContainerPreference.NEAREST)
        mutable.clear()
        assertEquals(listOf(a, b), input.positions)
        assertFailsWith<UnsupportedOperationException> { (input.positions as MutableList<*>).clear() }
        for (invalid in listOf(emptyList(), listOf(a, a), (0..8).map { NpcBlockPosition(it, 64, 0) })) {
            assertFailsWith<IllegalArgumentException> { OperationContainers(invalid) }
        }
        val redirects = TaskPublicAmendments.change(OperationChange.Recipients(input)) as TaskChange.Redirect
        assertEquals(listOf(a, b), redirects.recipients.positions)
        assertEquals(ContainerPreference.NEAREST, redirects.recipients.preference)
        val sources = TaskPublicAmendments.change(OperationChange.Sources(input)) as TaskChange.Sources
        assertEquals(redirects.recipients, sources.sources)
        assertNull((TaskPublicAmendments.change(OperationChange.Sources(null)) as TaskChange.Sources).sources)
        assertEquals(TaskChange.Quantity(17, QuantityChangeMode.ADD), TaskPublicAmendments.change(OperationChange.Quantity(17, OperationQuantityMode.ADD)))
        assertEquals(TaskChange.ExtendTime(77), TaskPublicAmendments.change(OperationChange.ExtendTime(77)))
        for (amount in listOf(0, -1, 2305, Int.MAX_VALUE)) {
            assertNotNull(TaskChanges.validationProblem(TaskPublicAmendments.change(OperationChange.Quantity(amount))))
        }
        for (ticks in listOf(0, -1, 72001, Int.MAX_VALUE)) {
            assertNotNull(TaskChanges.validationProblem(TaskPublicAmendments.change(OperationChange.ExtendTime(ticks))))
        }
    }

    @Test fun typedReceiptsNeverAttributeAnotherPayloadOrActorToTheCaller() {
        val r = TaskRecord.start(UUID.randomUUID(), NavigateTaskDefinition("minecraft:overworld", NpcPosition(1.0, 64.0, 0.0)), emptyList())
        val request = TaskAmendmentRequest(r.id, UUID.randomUUID(), UUID.randomUUID(), 0, 100, 200, TaskChange.ExtendTime(77))
        r.amendments.pending = request
        assertEquals(OperationAmendmentOutcome.PENDING, TaskPublicAmendments.receipt(r, request)?.outcome)
        assertNull(TaskPublicAmendments.receipt(r, request.copy(change = TaskChange.ExtendTime(78))))
        assertNull(TaskPublicAmendments.receipt(r, request.copy(actorUuid = UUID.randomUUID())))
        r.finish(TaskStatus.CANCELLED, TaskReason.USER_CANCELLED, "cancelled before pending amendment")
        val rejected = assertNotNull(TaskPublicAmendments.receipt(r, request))
        assertEquals(OperationAmendmentOutcome.REJECTED, rejected.outcome)
        assertEquals(0, rejected.revision)
        assertNull(TaskPublicAmendments.receipt(r, request.copy(issuedTick = 99)))
        r.amendments.receipts.clear()
        for (outcome in listOf(TaskAmendmentOutcome.APPLIED, TaskAmendmentOutcome.EXPIRED)) {
            r.amendments.receipts.add(TaskAmendmentReceipt(request, if (outcome == TaskAmendmentOutcome.APPLIED) 1 else 0, outcome, "recorded result"))
            val copied = assertNotNull(TaskPublicAmendments.receipt(r, request))
            assertEquals(outcome.name, copied.outcome.name)
            assertEquals(request.requestId, copied.requestId)
            r.amendments.receipts.clear()
            assertEquals("recorded result", copied.detail)
            assertNull(TaskPublicAmendments.receipt(r, request))
        }
    }
}
