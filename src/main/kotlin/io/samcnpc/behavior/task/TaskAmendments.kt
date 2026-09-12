package io.samcnpc.behavior.task

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Authoritative revision gate shared by commands and future public typed controls. */
internal object TaskAmendments {
    fun automatic(server: MinecraftServer, npc: NpcFacade, actor: ServerPlayer, change: TaskChange): NpcActionResult {
        val record = TaskStore.forServer(server).get(npc.npcUuid) ?: return NpcActionResult.rejected("no assigned task", NpcActionCode.NOT_READY)
        val now = npc.snapshot().gameTime
        return request(server, npc, actor, TaskAmendmentRequest(record.id, UUID.randomUUID(), actor.uuid, record.amendments.revision, now, now + 1200, change))
    }
    fun request(server: MinecraftServer, npc: NpcFacade, actor: ServerPlayer, request: TaskAmendmentRequest): NpcActionResult {
        check(server.isSameThread)
        val snapshot = npc.snapshot()
        if (actor.server !== server || actor.uuid != request.actorUuid || !authorized(server, snapshot, request.actorUuid)) return NpcActionResult.rejected("amendment actor is not the current summoner or operator", NpcActionCode.PERMISSION_DENIED)
        if (actor.serverLevel().dimension().location().toString() != snapshot.dimensionId ||
            TaskNavigator.distanceSquared(NpcPosition(actor.x, actor.y, actor.z), snapshot.position) > 256.0 * 256.0) return NpcActionResult.rejected("NPC must be loaded in the actor's dimension and within 256 blocks", NpcActionCode.OUT_OF_RANGE)
        val store = TaskStore.forServer(server)
        val record = store.get(npc.npcUuid) ?: return NpcActionResult.rejected("no assigned task", NpcActionCode.NOT_READY)
        if (record.id != request.taskId) return NpcActionResult.rejected("task identity changed; amendment rejected", NpcActionCode.CONFLICT)
        val replay = record.amendments.replay(request)
        if (replay != null) return if (replay.startsWith("CONFLICT:")) NpcActionResult.rejected(replay, NpcActionCode.CONFLICT) else NpcActionResult.succeeded(replay)
        val problem = request.validationProblem()
        if (problem != null) return NpcActionResult.rejected(problem)
        if (record.status.terminal) return NpcActionResult.rejected("task already has a final report", NpcActionCode.NOT_READY)
        if (request.expectedRevision != record.amendments.revision) return NpcActionResult.rejected("stale revision: expected=${request.expectedRevision}; current=${record.amendments.revision}", NpcActionCode.CONFLICT)
        if (snapshot.gameTime < request.issuedTick || snapshot.gameTime >= request.expiresTick) return NpcActionResult.rejected("request is expired or issued in the future", NpcActionCode.NOT_READY)
        if (record.amendments.pending != null) return NpcActionResult.rejected("another amendment is pending; no task change applied", NpcActionCode.CONFLICT)
        if (record.amendments.receipts.size >= TaskAmendmentState.MAX_REQUESTS) return NpcActionResult.rejected("task amendment history is full; replay protection is retained", NpcActionCode.NOT_READY)
        try { TaskAmendmentPreparation.proposed(record, request, snapshot) }
        catch (error: IllegalArgumentException) { return NpcActionResult.rejected(error.message ?: "invalid proposed definition") }
        if (!TaskAmendmentPreparation.boundary(record, request.change)) {
            record.amendments.pending = request; store.changed()
            return NpcActionResult.succeeded("PENDING: request=${request.requestId}; revision=${record.amendments.revision}; finish current work/cleanup before applying; expires=${request.expiresTick}")
        }
        return apply(server, record, request, npc, npc.worldView())
    }
    /** Pending changes observe only until the old operation reaches a safe boundary. */
    fun observe(server: MinecraftServer, record: TaskRecord, npc: NpcFacade, world: NpcWorldView) {
        val request = record.amendments.pending ?: return
        val snapshot = npc.snapshot()
        val outcome = when {
            snapshot.gameTime >= request.expiresTick || snapshot.gameTime < request.issuedTick -> TaskAmendmentOutcome.EXPIRED
            !authorized(server, snapshot, request.actorUuid) || request.expectedRevision != record.amendments.revision -> TaskAmendmentOutcome.REJECTED
            else -> null
        }
        if (outcome != null) {
            rejectPending(server, record, request, outcome, "pending amendment $outcome; original objective retained")
            return
        }
        if (!TaskAmendmentPreparation.boundary(record, request.change)) return
        val result = apply(server, record, request, npc, world)
        if (result.status != NpcActionStatus.SUCCEEDED) rejectPending(server, record, request, TaskAmendmentOutcome.REJECTED, result.detail)
    }
    private fun apply(server: MinecraftServer, record: TaskRecord, request: TaskAmendmentRequest, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val candidate = try { TaskAmendmentPreparation.prepare(record, request, npc, world) }
        catch (error: IllegalArgumentException) { return NpcActionResult.rejected(error.message ?: "amendment preparation failed") }
        // No world tick or asynchronous action can interleave this replacement on the server thread.
        BehaviorRuntimeService.releaseTaskControl(server, record.npcUuid)
        record.frames.clear(); record.frames.addAll(candidate.frames)
        record.status = candidate.status; record.reason = candidate.reason
        record.completedInterruptions = candidate.completedInterruptions; record.lastCombat = candidate.lastCombat
        record.logistics.policy = candidate.logistics.policy
        record.logistics.cooldownRemaining = candidate.logistics.cooldownRemaining
        record.logistics.outcomes.clear(); record.logistics.outcomes.addAll(candidate.logistics.outcomes)
        record.reaction.policy = candidate.reaction.policy
        record.reaction.activeFrame = candidate.reaction.activeFrame
        record.reaction.cooldownRemaining = candidate.reaction.cooldownRemaining
        record.reaction.consumedProtectionHit = candidate.reaction.consumedProtectionHit
        record.reaction.handledDamage = candidate.reaction.handledDamage
        record.amendments.revision = candidate.amendments.revision
        record.amendments.objectiveId = candidate.amendments.objectiveId
        record.amendments.receipts.clear(); record.amendments.receipts.addAll(candidate.amendments.receipts)
        record.amendments.objectives.clear(); record.amendments.objectives.addAll(candidate.amendments.objectives)
        record.amendments.pending = null
        record.detail = candidate.detail
        TaskStore.forServer(server).changed()
        return NpcActionResult.succeeded("APPLIED: request=${request.requestId}; ${record.detail}")
    }
    private fun rejectPending(server: MinecraftServer, record: TaskRecord, request: TaskAmendmentRequest, outcome: TaskAmendmentOutcome, detail: String) {
        record.amendments.pending = null
        record.amendments.receipts.add(TaskAmendmentReceipt(request, record.amendments.revision, outcome, detail.take(256)))
        TaskStore.forServer(server).changed()
    }
    private fun authorized(server: MinecraftServer, snapshot: NpcSnapshot, actorUuid: UUID): Boolean =
        snapshot.summonerUuid == actorUuid || server.playerList.getPlayer(actorUuid)?.hasPermissions(2) == true
}
