package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

internal object TaskPublicAmendments {
    fun request(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID, input: OperationAmendmentRequest): OperationReply =
        TaskSupervision.withNpc(server, actor, npcUuid) { npc ->
            val request = TaskAmendmentRequest(input.taskId, input.requestId, actor.uuid,
                input.expectedDefinitionRevision, input.issuedTick, input.expiresTick, change(input.change))
            val result = TaskAmendments.request(server, npc, actor, request)
            val record = TaskStore.forServer(server).get(npcUuid)
            val receipt = if (record?.id == input.taskId) receipt(record, request) else null
            OperationReply(result, TaskSupervision.report(server, npc).observation, receipt)
        }

    internal fun change(input: OperationChange): TaskChange = when (input) {
        is OperationChange.Quantity -> TaskChange.Quantity(input.amount, when (input.mode) {
            OperationQuantityMode.TOTAL -> QuantityChangeMode.TOTAL
            OperationQuantityMode.ADD -> QuantityChangeMode.ADD
        })
        is OperationChange.Recipients -> TaskChange.Redirect(containers(input.containers))
        is OperationChange.Sources -> TaskChange.Sources(input.containers?.let(::containers))
        is OperationChange.ExtendTime -> TaskChange.ExtendTime(input.ticks)
        is OperationChange.Replace -> TaskChange.Replace(TaskPublicOrders.definition(input.order), when (input.objective) {
            OperationObjectiveMode.PRESERVE -> ObjectiveChangeMode.PRESERVE
            OperationObjectiveMode.NEW_OBJECTIVE -> ObjectiveChangeMode.NEW_OBJECTIVE
        })
        is OperationChange.Tactics -> TaskChange.Tactics(TaskPublicCombatOrders.tactics(input.tactics))
        is OperationChange.Reaction -> TaskChange.Reaction(TaskPublicPolicies.reaction(input.policy))
        is OperationChange.Logistics -> TaskChange.Logistics(TaskPublicPolicies.logistics(input.policy))
    }

    private fun containers(input: OperationContainers): ContainerChoices = ContainerChoices(input.positions, when (input.preference) {
        OperationContainerPreference.ORDERED -> ContainerPreference.ORDERED
        OperationContainerPreference.NEAREST -> ContainerPreference.NEAREST
    })

    internal fun receipt(record: TaskRecord, request: TaskAmendmentRequest): OperationAmendmentSnapshot? {
        val queued = record.amendments.pending
        if (queued != null && queued.same(request)) {
            return OperationAmendmentSnapshot(request.requestId, request.taskId, record.amendments.revision,
                OperationAmendmentOutcome.PENDING, "waiting for an existing safe work boundary")
        }
        val receipt = record.amendments.receipts.firstOrNull { it.request.requestId == request.requestId } ?: return null
        if (!receipt.request.same(request)) return null
        val outcome = when (receipt.outcome) {
            TaskAmendmentOutcome.APPLIED -> OperationAmendmentOutcome.APPLIED
            TaskAmendmentOutcome.REJECTED -> OperationAmendmentOutcome.REJECTED
            TaskAmendmentOutcome.EXPIRED -> OperationAmendmentOutcome.EXPIRED
        }
        return OperationAmendmentSnapshot(request.requestId, request.taskId, receipt.revision, outcome,
            receipt.detail.take(TaskRecord.MAX_DETAIL_LENGTH))
    }
}
