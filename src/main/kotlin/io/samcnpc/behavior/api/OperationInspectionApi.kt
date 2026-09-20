package io.samcnpc.behavior.api

import io.samcnpc.behavior.task.TaskInspections
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcBodyInspection
import io.samcnpc.core.api.NpcSnapshot
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

object OperationInspectionApi {
    const val VERSION = 1
    /** Same connected-summoner/operator, dimension and distance checks as task supervision. */
    fun inspect(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID): OperationInspectionReply =
        TaskInspections.capture(server, actor, npcUuid)
}

data class OperationInspectionReply(val result: NpcActionResult, val inspection: OperationInspection?)
class OperationInspection internal constructor(
    val physical: NpcSnapshot,
    val body: NpcBodyInspection,
    val operation: OperationObservation,
    frames: List<OperationFrameInspection>,
) {
    val version: Int get() = OperationInspectionApi.VERSION
    val frames: List<OperationFrameInspection> = java.util.List.copyOf(frames)
}
class OperationFrameInspection internal constructor(
    val frameId: UUID,
    val definition: OperationDefinitionSnapshot,
    val progress: OperationProgress,
)
