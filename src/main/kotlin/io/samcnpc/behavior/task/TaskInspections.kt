package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.observation.OperationGenerationRegistry
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

internal object TaskInspections {
    fun capture(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID, worldRequest: OperationWorldRequest? = null): OperationInspectionReply {
        var inspection: OperationInspection? = null
        val reply = TaskSupervision.withNpc(server, actor, npcUuid) { npc ->
            val observed = TaskSupervision.report(server, npc)
            if (observed.result.status != NpcActionStatus.SUCCEEDED) return@withNpc observed
            val body = npc.inspectBody() ?: return@withNpc OperationReply(
                NpcActionResult.unsupported("NPC does not expose own-body inspection"), null)
            val physical = npc.snapshot()
            val generations = when (val value = BehaviorRuntimeService.inspectionGenerations(server, npcUuid,
                physical.dimensionId, npc.inventoryLoadSnapshot()?.generation, physical.gameTime)) {
                is OperationGenerationRegistry.Result.Available -> value.value
                is OperationGenerationRegistry.Result.Unavailable -> return@withNpc OperationReply(
                    NpcActionResult.rejected("inspection lifecycle unavailable: " + value.reason.name, NpcActionCode.NOT_READY), null)
            }
            val equipment = body.equipment
            val copied = physical.copy(equipment = NpcEquipmentKnowledge(body.mainHand.knowledge,
                equipment.getValue(NpcInspectionSlot.OFF_HAND).knowledge, equipment.getValue(NpcInspectionSlot.HEAD).knowledge,
                equipment.getValue(NpcInspectionSlot.CHEST).knowledge, equipment.getValue(NpcInspectionSlot.LEGS).knowledge,
                equipment.getValue(NpcInspectionSlot.FEET).knowledge, equipment.getValue(NpcInspectionSlot.AMMUNITION).knowledge,
                equipment.getValue(NpcInspectionSlot.TOTEM).knowledge),
                recentCompletions = java.util.List.copyOf(physical.recentCompletions.takeLast(16).map {
                    it.copy(result = it.result.copy(detail = it.result.detail.take(256)))
                }))
            val record = TaskStore.forServer(server).get(npcUuid)
            val frames = mutableListOf<OperationFrameInspection>()
            for (frame in record?.frames.orEmpty()) {
                val terminalCombat = if (record != null && frame === record.primary && record.status.terminal) record.lastCombat else null
                val definition = TaskDefinitionInspections.capture(frame.definition)
                val progress = TaskProgressInspections.capture(frame, terminalCombat)
                frames.add(OperationFrameInspection(frame.id, definition, progress, TaskResourceInspections.capture(frame)))
            }
            val world = if (worldRequest == null) null else TaskWorldInspections.capture(npc, copied, worldRequest)
            val reservations = TaskReservationInspections.capture(npcUuid, physical.gameTime)
            inspection = OperationInspection(copied, body, generations, checkNotNull(observed.observation), frames, world, reservations)
            observed
        }
        return OperationInspectionReply(reply.result, inspection)
    }
}
