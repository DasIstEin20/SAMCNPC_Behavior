package io.samcnpc.behavior.gametest

import com.google.gson.JsonObject
import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object OperationDocumentGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 500, batch = "operation_documents")
    fun decodedOrderAndAmendmentKeepAuthorityReplayAndPhysicalArrival(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level, "JsonOrder")
        val stranger = GameTestActor(helper.level, "JsonOther")
        val arena = CombatGameTestArena(helper, actor.player)
        val server = helper.level.server
        val destination = NpcPosition(arena.start.x + 8.0, arena.start.y, arena.start.z)
        arena.onReady { npc ->
            val observed = checkNotNull(OperationSupervisionApi.observe(server, actor.player, npc.npcUuid).observation)
            val parameters = JsonObject()
            parameters.addProperty("dimensionId", observed.dimensionId)
            parameters.add("destination", JsonObject().also {
                it.addProperty("x", destination.x); it.addProperty("y", destination.y); it.addProperty("z", destination.z)
            })
            val document = JsonObject()
            document.addProperty("documentVersion", 1); document.addProperty("type", "samcnpc:navigate")
            document.addProperty("definitionVersion", 1); document.add("parameters", parameters)
            val decoded = OperationDocumentApi.decodeOrder(document.toString())
            check(decoded is OperationDocumentResult.Accepted)
            check(TaskStore.forServer(server).get(npc.npcUuid) == null)
            val request = OperationAssignmentRequest(null, observed.observedTick, observed.observedTick + 100, decoded.value)
            check(OperationSupervisionApi.assign(server, stranger.player, npc.npcUuid, request).result.code == NpcActionCode.PERMISSION_DENIED)
            check(TaskStore.forServer(server).get(npc.npcUuid) == null)
            val assigned = OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, request)
            check(assigned.result.status == NpcActionStatus.SUCCEEDED)
            check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, request).result.code == NpcActionCode.CONFLICT)
            val current = checkNotNull(assigned.observation)
            val task = checkNotNull(current.task)
            val change = OperationDocumentApi.decodeChange("""{"documentVersion":1,"type":"EXTEND_TIME","parameters":{"ticks":20}}""")
            check(change is OperationDocumentResult.Accepted)
            val amendment = OperationAmendmentRequest(task.taskId, UUID.randomUUID(), task.definitionRevision,
                current.observedTick, current.observedTick + 100, change.value)
            check(OperationSupervisionApi.amend(server, stranger.player, npc.npcUuid, amendment).result.code == NpcActionCode.PERMISSION_DENIED)
            val result = OperationSupervisionApi.amend(server, actor.player, npc.npcUuid, amendment)
            check(result.amendment?.outcome == OperationAmendmentOutcome.APPLIED)
            val again = OperationSupervisionApi.amend(server, actor.player, npc.npcUuid, amendment)
            check(again.amendment == result.amendment)
            check(again.observation?.task?.frames == result.observation?.task?.frames)
        }
        arena.observe { npc, record ->
            check(record.status != TaskStatus.FAILED) { record.detail }
            if (record.status == TaskStatus.COMPLETED) {
                check(record.reason == TaskReason.ARRIVED)
                check(TaskNavigator.distanceSquared(npc.snapshot().position, destination) <= 1.0)
                check(TaskNavigator.distanceSquared(npc.snapshot().position, arena.start) > 36.0)
                arena.succeed(npc, record)
                actor.close(); stranger.close()
            }
        }
    }
}
