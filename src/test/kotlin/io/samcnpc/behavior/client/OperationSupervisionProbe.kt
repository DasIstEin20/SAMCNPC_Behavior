package io.samcnpc.behavior.client

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Actual integrated-server consumer of public APIs; pauses each work family for five ticks. */
internal class OperationSupervisionProbe(
    private val server: MinecraftServer,
    private val actor: ServerPlayer,
    private val npcUuid: UUID,
) {
    private var before: OperationTaskSnapshot? = null
    private var generations: OperationGenerations? = null
    private var subscription: OperationSubscription? = null
    private val eventBatches = mutableListOf<OperationEventBatch>()
    private var heldTicks = 0
    var complete = false
        private set

    fun tick(): Boolean {
        if (complete) return true
        val reply = OperationSupervisionApi.observe(server, actor, npcUuid)
        check(reply.result.status == NpcActionStatus.SUCCEEDED) { reply.result.detail }
        val observation = checkNotNull(reply.observation)
        val task = checkNotNull(observation.task)
        val original = before
        if (original == null) {
            val service = CoreNpcApi.service(server)
            val npc = checkNotNull(service.find(npcUuid)?.let(service::runtime))
            check(npc.snapshot().summonerUuid != actor.uuid && actor.hasPermissions(2)) {
                "client fixture must exercise actual operator access to another summoner's NPC"
            }
            val inspectionReply = OperationInspectionApi.inspect(server, actor, npcUuid, OperationWorldRequest())
            check(inspectionReply.result.status == NpcActionStatus.SUCCEEDED) { inspectionReply.result.detail }
            val inspection = checkNotNull(inspectionReply.inspection)
            generations = inspection.generations
            val world = checkNotNull(inspection.world)
            check(world.observedTick == inspection.physical.gameTime && world.dimensionId == inspection.physical.dimensionId)
            check(world.source == OperationObservationSource.NPC_VISUAL_SENSOR)
            check(world.entities is NpcVisualEntityScan.Observed && world.entities.entities.size <= 16)
            check(world.blocks.isEmpty())
            check(inspection.body.inventory.size == 36 && inspection.physical.npcUuid == npcUuid)
            check(inspection.operation.task?.taskId == task.taskId)
            check(inspection.frames.map { it.frameId } == task.frames.map { it.frameId })
            for ((index, frame) in inspection.frames.withIndex()) {
                verifyAccounting(frame)
                check(frame.definition.operationId == task.frames[index].operationId)
                check(frame.definition.definitionVersion == task.frames[index].definitionVersion)
                check(frame.definition.parameters.fields["dimensionId"] == OperationValue.Text(observation.dimensionId))
            }
            check(inspection.journal == OperationJournalState.NotRecorded)
            subscription = checkNotNull(OperationEventApi.subscribe(server, actor, npcUuid) { eventBatches.add(it) }.subscription)
            before = task
            val paused = OperationSupervisionApi.control(server, actor, npcUuid, request(observation, OperationControl.PAUSE))
            check(paused.result.status == NpcActionStatus.SUCCEEDED) { paused.result.detail }
            check(paused.observation?.task?.state == OperationTaskState.PAUSED)
            check(paused.observation?.task?.controlRevision == task.controlRevision + 1)
            return false
        }
        check(task.taskId == original.taskId && task.definitionRevision == original.definitionRevision)
        check(task.controlRevision == original.controlRevision + 1 && task.state == OperationTaskState.PAUSED)
        check(task.frames == original.frames) { "pause changed original work budgets/frames" }
        val service = CoreNpcApi.service(server)
        val body = checkNotNull(service.find(npcUuid)?.let(service::runtime)).snapshot()
        check(body.navigation == null && body.blockBreak == null && body.itemUse == null && body.rangedAttack == null)
        if (++heldTicks < 5) return false
        val current = checkNotNull(OperationInspectionApi.inspect(server, actor, npcUuid).inspection)
        check(current.generations == generations) { "Ordinary ticks/control revisions changed body lifetime" }
        check(checkNotNull(subscription).state == OperationSubscriptionState.ACTIVE)
        check(current.journal is OperationJournalState.Recorded)
        val events = eventBatches.flatMap { it.journal.events }
        check(events.count { it.kind == OperationEventKind.CONTROL_CHANGED } == 1)
        check(events.none { it.kind.decisionBoundary })
        check(eventBatches.all { it.generations == current.generations && it.npcUuid == npcUuid })
        val decoded = OperationDocumentApi.decodeChange("""{"documentVersion":1,"type":"EXTEND_TIME","parameters":{"ticks":20}}""")
        check(decoded is OperationDocumentResult.Accepted) { decoded.toString() }
        val amendment = OperationAmendmentRequest(task.taskId, UUID.randomUUID(), task.definitionRevision,
            observation.observedTick, observation.observedTick + 100, decoded.value)
        val changed = OperationSupervisionApi.amend(server, actor, npcUuid, amendment)
        check(changed.result.status == NpcActionStatus.SUCCEEDED && changed.amendment?.outcome == OperationAmendmentOutcome.APPLIED) { changed.result.detail }
        val revised = checkNotNull(changed.observation)
        val expectedFrames = original.frames.map { it.copy(remainingTicks = it.remainingTicks + 20, durationTicks = it.durationTicks + 20) }
        check(revised.task?.frames == expectedFrames && revised.task?.definitionRevision == original.definitionRevision + 1)
        val resumed = OperationSupervisionApi.control(server, actor, npcUuid, request(revised, OperationControl.RESUME))
        check(resumed.result.status == NpcActionStatus.SUCCEEDED) { resumed.result.detail }
        check(resumed.observation?.task?.controlRevision == original.controlRevision + 2)
        check(resumed.observation?.task?.frames == expectedFrames)
        val receipt = OperationSupervisionApi.amendmentReceipt(server, actor, npcUuid, amendment)
        check(receipt.amendment == changed.amendment && receipt.observation?.task?.frames == expectedFrames)
        val absent = OperationSupervisionApi.amendmentReceipt(server, actor, npcUuid,
            amendment.copy(requestId = UUID.randomUUID(), expectedDefinitionRevision = checkNotNull(revised.task).definitionRevision))
        check(absent.result.code == NpcActionCode.NOT_FOUND && absent.amendment == null &&
            absent.observation?.task?.frames == expectedFrames)
        val replay = OperationSupervisionApi.amend(server, actor, npcUuid, amendment)
        check(replay.amendment == changed.amendment && replay.observation?.task?.frames == expectedFrames)
        val conflict = OperationSupervisionApi.amend(server, actor, npcUuid, amendment.copy(change = OperationChange.ExtendTime(21)))
        check(conflict.result.code == NpcActionCode.CONFLICT && conflict.amendment == null)
        check(conflict.observation?.task?.frames == expectedFrames)
        check(OperationEventApi.unsubscribe(server, checkNotNull(subscription)).status == NpcActionStatus.SUCCEEDED)
        check(checkNotNull(subscription).state == OperationSubscriptionState.UNSUBSCRIBED)
        complete = true
        return true
    }

    private fun verifyAccounting(frame: OperationFrameInspection) {
        val withoutLedger = setOf("samcnpc:navigate", "samcnpc:explore", "samcnpc:attack", "samcnpc:defend",
            "samcnpc:attack_area", "samcnpc:patrol")
        if (frame.definition.operationId in withoutLedger) {
            check(frame.resources == OperationResourceInspection.NotTracked)
            return
        }
        val resources = frame.resources
        check(resources is OperationResourceInspection.Checkpoint)
        check(resources.items.size <= 64)
        for (item in resources.items) {
            check(item.counters.all { it.unit == OperationCountUnit.ITEMS })
            if (resources.kind == OperationResourceLedgerKind.PHYSICAL || resources.kind == OperationResourceLedgerKind.PRODUCED) {
                val counts = item.counters.associate { it.name to it.value }
                check(counts.getValue("initial") + counts.getValue("gathered") + counts.getValue("supplied") ==
                    counts.getValue("retained") + counts.getValue("consumed") + counts.getValue("delivered") + counts.getValue("lost"))
            }
        }
    }

    private fun request(observation: OperationObservation, control: OperationControl): OperationControlRequest {
        val task = checkNotNull(observation.task)
        return OperationControlRequest(task.taskId, task.controlRevision, task.definitionRevision,
            observation.observedTick, observation.observedTick + 100, control)
    }
}
