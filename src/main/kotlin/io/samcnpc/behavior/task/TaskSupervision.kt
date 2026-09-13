package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Authorization is observed again at effect time; this adapter never dispatches commands. */
internal object TaskSupervision {
    fun observe(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID): OperationReply =
        withNpc(server, actor, npcUuid) { npc -> report(server, npc) }

    fun control(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID, request: OperationControlRequest): OperationReply =
        withNpc(server, actor, npcUuid) { npc ->
            val observed = report(server, npc)
            if (observed.result.status != NpcActionStatus.SUCCEEDED) return@withNpc observed
            val record = TaskStore.forServer(server).get(npcUuid)
                ?: return@withNpc OperationReply(NpcActionResult.rejected("NPC has no durable task", NpcActionCode.NOT_READY), observed.observation)
            val rejection = TaskControlGuard.rejection(record, request, npc.snapshot().gameTime)
            if (rejection != null) return@withNpc OperationReply(rejection, observed.observation)
            val result = when (request.control) {
                OperationControl.PAUSE -> TaskService.pause(server, npcUuid)
                OperationControl.RESUME -> TaskService.resume(server, npcUuid)
                OperationControl.CANCEL -> TaskService.cancel(server, npcUuid)
            }
            OperationReply(result, report(server, npc).observation)
        }

    internal fun withNpc(
        server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID,
        action: (NpcFacade) -> OperationReply,
    ): OperationReply {
        if (!server.isSameThread) return denied("operation supervision requires the authoritative server thread", NpcActionCode.NOT_READY)
        if (actor.server !== server || server.playerList.getPlayer(actor.uuid) !== actor) {
            return denied("actor is no longer the current connected player", NpcActionCode.PERMISSION_DENIED)
        }
        val service = CoreNpcApi.service(server)
        val handle = service.find(npcUuid) ?: return denied("NPC is not loaded", NpcActionCode.NOT_FOUND)
        val npc = service.runtime(handle) ?: return denied("NPC is no longer loaded", NpcActionCode.NOT_FOUND)
        val snapshot = npc.snapshot()
        if (snapshot.summonerUuid != actor.uuid && !actor.hasPermissions(2)) {
            return denied("only the summoner or an operator can inspect or control this task", NpcActionCode.PERMISSION_DENIED)
        }
        if (actor.serverLevel().dimension().location().toString() != snapshot.dimensionId ||
            TaskNavigator.distanceSquared(NpcPosition(actor.x, actor.y, actor.z), snapshot.position) > 256.0 * 256.0) {
            return denied("NPC must be in the actor's dimension and within 256 blocks", NpcActionCode.OUT_OF_RANGE)
        }
        return action(npc)
    }

    internal fun report(server: MinecraftServer, npc: NpcFacade): OperationReply {
        val store = TaskStore.forServer(server)
        val problem = store.problemFor(npc.npcUuid)
        if (problem != null) return denied(problem.take(TaskRecord.MAX_DETAIL_LENGTH), NpcActionCode.NOT_READY)
        val body = npc.snapshot()
        val record = store.get(npc.npcUuid)
        val task = if (record == null) null else snapshot(record)
        return OperationReply(NpcActionResult.succeeded("operation observation"),
            OperationObservation(npc.npcUuid, body.dimensionId, body.gameTime, task))
    }

    internal fun snapshot(record: TaskRecord): OperationTaskSnapshot {
        val state = when (record.status) {
            TaskStatus.RUNNING -> OperationTaskState.RUNNING
            TaskStatus.WAITING -> OperationTaskState.WAITING
            TaskStatus.PAUSED -> OperationTaskState.PAUSED
            TaskStatus.COMPLETED -> OperationTaskState.COMPLETED
            TaskStatus.CANCELLED -> OperationTaskState.CANCELLED
            TaskStatus.FAILED -> OperationTaskState.FAILED
        }
        return OperationTaskSnapshot(record.id, record.amendments.objectiveId, record.amendments.revision,
            record.controlRevision, state, record.reason.name, record.detail.take(TaskRecord.MAX_DETAIL_LENGTH),
            record.totalFailures, record.completedInterruptions, record.amendments.pending?.requestId,
            record.frames.map { frame ->
                OperationFrameSnapshot(frame.id, frame.definition.operationId, frame.definition.version,
                    frame.remainingTicks, frame.definition.budget.ticks, frame.failures,
                    frame.definition.budget.attempts, frame.waitTicks, frame.reason.name)
            })
    }

    private fun denied(detail: String, code: NpcActionCode): OperationReply =
        OperationReply(NpcActionResult.rejected(detail, code), null)
}
