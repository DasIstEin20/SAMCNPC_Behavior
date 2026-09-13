package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskPublicPolicyChangesTest {
    private val at = NpcPosition(0.5, 65.0, 0.5)
    private val containers = OperationContainers(listOf(NpcBlockPosition(4, 65, 0)))
    private fun valid(change: OperationChange): TaskChange {
        val mapped = TaskPublicAmendments.change(change)
        assertNull(TaskChanges.validationProblem(mapped))
        val bytes = TaskChangeCodec.write(mapped)
        assertEquals(bytes, TaskChangeCodec.write(TaskChangeCodec.read(bytes)))
        return mapped
    }
    private fun invalid(change: OperationChange) = assertNotNull(TaskChanges.validationProblem(TaskPublicAmendments.change(change)))

    @Test fun replacementRetainsExplicitObjectiveAndCannotResetDefinitionLimits() {
        val old = OperationOrder.Navigate("minecraft:overworld", at)
        for (mode in OperationObjectiveMode.entries) {
            val change = valid(OperationChange.Replace(old, mode)) as TaskChange.Replace
            assertEquals(mode.name, change.objective.name)
            assertEquals(at, (change.definition as NavigateTaskDefinition).destination)
        }
        invalid(OperationChange.Replace(old.copy(speed = 5F)))
        val record = TaskRecord.start(UUID.randomUUID(), TaskPublicOrders.definition(old), emptyList())
        val request = TaskAmendmentRequest(record.id, UUID.randomUUID(), UUID.randomUUID(), 0, 100, 200,
            TaskPublicAmendments.change(OperationChange.Replace(old)))
        record.amendments.receipts.add(TaskAmendmentReceipt(request, 1, TaskAmendmentOutcome.APPLIED, "recorded once"))
        assertEquals(OperationAmendmentOutcome.APPLIED, TaskPublicAmendments.receipt(record, request)?.outcome)
        assertNull(TaskPublicAmendments.receipt(record, request.copy(change = TaskPublicAmendments.change(
            OperationChange.Replace(old.copy(destination = at.copy(x = 2.0)))))))
    }

    @Test fun reactionModesRequireTheirExactSubjectAnchorAndFilterContract() {
        for (mode in OperationReactionMode.entries) {
            val protects = mode == OperationReactionMode.PROTECT_SUMMONER || mode == OperationReactionMode.PROTECT_UNIT
            val policy = OperationReactionPolicy(mode, anchor = if (protects || mode == OperationReactionMode.AREA) at else null,
                subjectUuid = if (protects) UUID(1, 2) else null,
                filter = if (mode == OperationReactionMode.AREA) NpcEntityTypeFilter.of(setOf("minecraft:husk")) else NpcEntityTypeFilter.ANY)
            val change = valid(OperationChange.Reaction(policy)) as TaskChange.Reaction
            assertEquals(mode.name, change.policy.mode.name)
        }
        invalid(OperationChange.Reaction(OperationReactionPolicy(OperationReactionMode.PROTECT_UNIT)))
        invalid(OperationChange.Reaction(OperationReactionPolicy(OperationReactionMode.AREA, anchor = at)))
        invalid(OperationChange.Reaction(OperationReactionPolicy(anchor = at)))
        invalid(OperationChange.Reaction(OperationReactionPolicy(cooldownTicks = 0)))
    }

    @Test fun logisticsCannotCycleSupplyIntoUnloadOrEscapeItsFiniteReturnBudget() {
        val supply = OperationInventoryWork.Supply(listOf(OperationStockNeed("minecraft:bread", 2, 8, 1)), containers)
        val unload = OperationInventoryWork.Unload(listOf(OperationItemReserve("minecraft:bread", 8)), containers)
        val policy = OperationLogisticsPolicy(at, supply, unload, OperationInventoryWork.Pickup(listOf("minecraft:bread")))
        valid(OperationChange.Logistics(policy)); valid(OperationChange.Logistics(OperationLogisticsPolicy()))
        invalid(OperationChange.Logistics(policy.copy(unload = OperationInventoryWork.Unload(listOf(OperationItemReserve("minecraft:bread", 7)), containers))))
        invalid(OperationChange.Logistics(policy.copy(anchor = null)))
        invalid(OperationChange.Logistics(policy.copy(workTicks = 1190)))
        invalid(OperationChange.Logistics(policy.copy(maxSteps = 129)))
        invalid(OperationChange.Logistics(OperationLogisticsPolicy(anchor = at)))
    }

    @Test fun publicTacticsRetainHardConstraintsAndExactReplayPayload() {
        val tactics = OperationCombatTactics(preference = OperationWeaponPreference.RANGED,
            allowed = OperationWeaponAllowance.RANGED, retreatAt = 0.2, returnAt = 0.7,
            rangedMinDistance = 5.0, rangedMaxDistance = 18.0)
        val change = valid(OperationChange.Tactics(tactics)) as TaskChange.Tactics
        assertEquals(0.2, change.tactics.retreatAt); assertEquals(0.7, change.tactics.returnAt)
        assertEquals(5.0, change.tactics.rangedMinDistance); assertEquals(18.0, change.tactics.rangedMaxDistance)
        invalid(OperationChange.Tactics(tactics.copy(allowed = OperationWeaponAllowance.MELEE)))
        assertNotEquals(TaskChangeCodec.write(change), TaskChangeCodec.write(TaskPublicAmendments.change(
            OperationChange.Tactics(tactics.copy(useShield = false)))))
    }
}
