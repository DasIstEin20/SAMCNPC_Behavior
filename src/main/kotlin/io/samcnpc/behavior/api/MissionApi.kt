package io.samcnpc.behavior.api

import io.samcnpc.behavior.mission.MissionDocuments
import io.samcnpc.behavior.mission.MissionService
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Finite deterministic sequencing. No world primitives or alternate admission path. */
object MissionApi {
    const val DOCUMENT_VERSION = 1
    fun availableIds(): List<String> = BehaviorRuntimeService.missionIds()
    fun validate(json: String): ValidationReport = try {
        MissionDocuments.read(json); ValidationReport(true,emptyList())
    } catch (error: IllegalArgumentException) { ValidationReport(false,listOf(error.message.orEmpty().take(512))) }
    catch (error: com.google.gson.JsonParseException) { ValidationReport(false,listOf(error.message.orEmpty().take(512))) }
    fun start(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID, missionId: String) = MissionService.start(server,actor,npcUuid,missionId)
    fun control(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID, control: MissionControl) = MissionService.control(server,actor,npcUuid,control.name.lowercase(java.util.Locale.ROOT))
}
enum class MissionControl { STATUS, PAUSE, RESUME, CANCEL }
