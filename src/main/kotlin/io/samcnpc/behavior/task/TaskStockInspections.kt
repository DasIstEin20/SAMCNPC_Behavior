package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.OperationReply
import io.samcnpc.behavior.api.OperationStockReply
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

internal object TaskStockInspections {
    fun capture(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID,
                dimensionId: String, query: NpcStockQuery): OperationStockReply {
        var stock: NpcStockRead? = null
        val reply = TaskSupervision.withNpc(server, actor, npcUuid) { npc ->
            val observed = TaskSupervision.report(server, npc)
            if (observed.result.status != NpcActionStatus.SUCCEEDED) return@withNpc observed
            if (npc.snapshot().dimensionId != dimensionId) return@withNpc OperationReply(
                NpcActionResult.rejected("stock target must be in the current dimension", NpcActionCode.OUT_OF_RANGE), null)
            stock = npc.worldView().observeVisibleStock(query)
            observed
        }
        return OperationStockReply(reply.result, stock)
    }
}
