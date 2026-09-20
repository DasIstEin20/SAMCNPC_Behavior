package io.samcnpc.behavior.api

import io.samcnpc.behavior.task.TaskStockInspections
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcStockQuery
import io.samcnpc.core.api.NpcStockRead
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Opt-in, authorized physical stock read; it never assigns work or caches a world observation. */
object OperationStockApi {
    const val VERSION = 1
    fun inspect(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID,
                dimensionId: String, query: NpcStockQuery): OperationStockReply =
        TaskStockInspections.capture(server, actor, npcUuid, dimensionId, query)
}

data class OperationStockReply(val result: NpcActionResult, val stock: NpcStockRead?)
