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
        val amendment = OperationAmendmentRequest(task.taskId, UUID.randomUUID(), task.definitionRevision,
            observation.observedTick, observation.observedTick + 100, OperationChange.ExtendTime(20))
        val changed = OperationSupervisionApi.amend(server, actor, npcUuid, amendment)
        check(changed.result.status == NpcActionStatus.SUCCEEDED && changed.amendment?.outcome == OperationAmendmentOutcome.APPLIED) { changed.result.detail }
        val revised = checkNotNull(changed.observation)
        val expectedFrames = original.frames.map { it.copy(remainingTicks = it.remainingTicks + 20, durationTicks = it.durationTicks + 20) }
        check(revised.task?.frames == expectedFrames && revised.task?.definitionRevision == original.definitionRevision + 1)
        val resumed = OperationSupervisionApi.control(server, actor, npcUuid, request(revised, OperationControl.RESUME))
        check(resumed.result.status == NpcActionStatus.SUCCEEDED) { resumed.result.detail }
        check(resumed.observation?.task?.controlRevision == original.controlRevision + 2)
        check(resumed.observation?.task?.frames == expectedFrames)
        val replay = OperationSupervisionApi.amend(server, actor, npcUuid, amendment)
        check(replay.amendment == changed.amendment && replay.observation?.task?.frames == expectedFrames)
        val conflict = OperationSupervisionApi.amend(server, actor, npcUuid, amendment.copy(change = OperationChange.ExtendTime(21)))
        check(conflict.result.code == NpcActionCode.CONFLICT && conflict.amendment == null)
        check(conflict.observation?.task?.frames == expectedFrames)
        complete = true
        return true
    }

    private fun request(observation: OperationObservation, control: OperationControl): OperationControlRequest {
        val task = checkNotNull(observation.task)
        return OperationControlRequest(task.taskId, task.controlRevision, task.definitionRevision,
            observation.observedTick, observation.observedTick + 100, control)
    }
}
