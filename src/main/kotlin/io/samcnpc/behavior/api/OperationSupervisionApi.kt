package io.samcnpc.behavior.api

import io.samcnpc.behavior.task.TaskPublicOrders
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.behavior.task.TaskSupervision
import io.samcnpc.behavior.task.TaskPublicAmendments
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Server-bound calls require the authoritative thread and the current connected player. */
object OperationSupervisionApi {
    /** Pure validation of the typed definition; world/authority checks occur at assignment. */
    fun validateOrder(order: OperationOrder): NpcActionResult = TaskPublicOrders.validate(order)

    fun assign(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID, request: OperationAssignmentRequest): OperationReply =
        TaskPublicOrders.assign(server, actor, npcUuid, request)

    fun observe(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID): OperationReply =
        TaskSupervision.observe(server, actor, npcUuid)

    fun control(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID, request: OperationControlRequest): OperationReply =
        TaskSupervision.control(server, actor, npcUuid, request)

    /** Read-only lookup for the exact original payload. A missing receipt never submits the amendment. */
    fun amendmentReceipt(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID,
                         request: OperationAmendmentRequest): OperationReply =
        TaskPublicAmendments.lookup(server, actor, npcUuid, request)

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
