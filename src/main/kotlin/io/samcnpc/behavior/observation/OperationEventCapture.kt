package io.samcnpc.behavior.observation

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.TaskSupervision
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Small task/completion read only: no inventory projection, visual scan or JSON on watched ticks. */
internal object OperationEventCapture {
    data class Sample(val generations: OperationGenerations, val tick: Long,
                      val task: OperationTaskSnapshot?, val completions: List<NpcActionCompletion>)
    data class Reply(val result: NpcActionResult, val sample: Sample?)

    fun read(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID): Reply {
        var sample: Sample? = null
        val reply = TaskSupervision.withNpc(server, actor, npcUuid) { npc ->
            val observation = TaskSupervision.report(server, npc)
            if (observation.result.status != NpcActionStatus.SUCCEEDED) return@withNpc observation
            val body = npc.snapshot()
            val captured = BehaviorRuntimeService.inspectionGenerations(server, npcUuid, body.dimensionId,
                npc.inventoryLoadSnapshot()?.generation, body.gameTime)
            if (captured !is OperationGenerationRegistry.Result.Available) return@withNpc OperationReply(
                NpcActionResult.rejected("event lifecycle unavailable", NpcActionCode.NOT_READY), null)
            sample = Sample(captured.value, body.gameTime, observation.observation?.task,
                java.util.List.copyOf(body.recentCompletions.takeLast(16)))
            observation
        }
        return Reply(reply.result, sample)
    }

    fun authorized(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID): NpcActionResult =
        TaskSupervision.withNpc(server, actor, npcUuid) {
            OperationReply(NpcActionResult.succeeded("event subscription authorized"), null)
        }.result
}
