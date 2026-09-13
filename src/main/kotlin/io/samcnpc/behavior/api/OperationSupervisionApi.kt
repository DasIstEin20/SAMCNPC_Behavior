package io.samcnpc.behavior.api

import io.samcnpc.behavior.task.TaskSupervision
import io.samcnpc.behavior.task.TaskPublicAmendments
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Call on the authoritative server thread with the current connected player. */
object OperationSupervisionApi {
    fun observe(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID): OperationReply =
        TaskSupervision.observe(server, actor, npcUuid)

    fun control(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID, request: OperationControlRequest): OperationReply =
        TaskSupervision.control(server, actor, npcUuid, request)

    fun amend(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID, request: OperationAmendmentRequest): OperationReply =
        TaskPublicAmendments.request(server, actor, npcUuid, request)
}

enum class OperationControl { PAUSE, RESUME, CANCEL }

/**
 * Compare-and-set control, not a claim of exactly-once delivery. A retry after a lost reply
 * returns a rejected result and a fresh observation; callers must not silently renew revisions.
 */
data class OperationControlRequest(
    val taskId: UUID,
    val expectedControlRevision: Long,
    val expectedDefinitionRevision: Int,
    val issuedTick: Long,
    val expiresTick: Long,
    val control: OperationControl,
)
