package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object OperationSupervisionGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 800, batch = "operation_supervision")
    fun currentActorExactTaskRevisionAndExpiryGateRealControlAcrossReload(helper: GameTestHelper) {
        val server = helper.level.server
        val actor = GameTestActor(helper.level, "Supervise")
        val stranger = GameTestActor(helper.level, "OtherControl")
        val arena = CombatGameTestArena(helper, actor.player)
        var stage = 0
        var heldTicks = 0
        var budget = 0
        var oldRequest: OperationControlRequest? = null
        var wrongThread: CompletableFuture<OperationReply>? = null
        var pausedAt: NpcPosition? = null
        arena.onReady { npc ->
            val empty = OperationSupervisionApi.observe(server, actor.player, npc.npcUuid)
            check(empty.result.status == NpcActionStatus.SUCCEEDED && checkNotNull(empty.observation).task == null)
            val view = checkNotNull(empty.observation)
            val order = OperationOrder.Navigate(view.dimensionId,
                NpcPosition(arena.start.x + 20, arena.start.y, arena.start.z), budget = OperationBudget(ticks = 700))
            val assign = OperationAssignmentRequest(null, view.observedTick, view.observedTick + 100, order)
            check(OperationSupervisionApi.assign(server, stranger.player, npc.npcUuid, assign).result.code == NpcActionCode.PERMISSION_DENIED)
            for (bad in listOf(assign.copy(expectedPriorTaskId = UUID.randomUUID()),
                assign.copy(expiresTick = view.observedTick), assign.copy(order = order.copy(speed = 9.0F)),
                assign.copy(order = order.copy(dimensionId = "minecraft:the_nether")))) {
                check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, bad).result.status == NpcActionStatus.REJECTED)
                check(TaskStore.forServer(server).get(npc.npcUuid) == null)
            }
            val assigned = OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, assign)
            check(assigned.result.status == NpcActionStatus.SUCCEEDED)
            val record = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
            val before = TaskCodec.write(record)
            check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, assign).result.code == NpcActionCode.CONFLICT)
            check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid,
                assign.copy(expectedPriorTaskId = record.id)).result.code == NpcActionCode.CONFLICT)
            check(TaskCodec.write(record) == before)
        }
        arena.observe { npc, record ->
            if (stage == 0 && npc.snapshot().navigation != null &&
                TaskNavigator.distanceSquared(npc.snapshot().position, arena.start) > 1) {
                val view = checkNotNull(OperationSupervisionApi.observe(server, actor.player, npc.npcUuid).observation)
                val task = checkNotNull(view.task)
                val request = OperationControlRequest(task.taskId, task.controlRevision, task.definitionRevision,
                    view.observedTick, view.observedTick + 100, OperationControl.PAUSE)
                oldRequest = request
                val before = TaskCodec.write(record)
                val denied = OperationSupervisionApi.control(server, stranger.player, npc.npcUuid, request)
                check(denied.result.code == NpcActionCode.PERMISSION_DENIED && denied.observation == null)
                check(OperationSupervisionApi.observe(server, actor.player, UUID.randomUUID()).result.code == NpcActionCode.NOT_FOUND)
                actor.player.teleportTo(helper.level, arena.start.x + 300, arena.start.y + 3, arena.start.z, 0F, 0F)
                check(OperationSupervisionApi.control(server, actor.player, npc.npcUuid, request).result.code == NpcActionCode.OUT_OF_RANGE)
                actor.player.teleportTo(helper.level, arena.start.x, arena.start.y + 3, arena.start.z + 8, 0F, 0F)
                stranger.player.teleportTo(helper.level, arena.start.x, arena.start.y + 3, arena.start.z + 7, 0F, 0F)
                server.playerList.op(stranger.player.gameProfile)
                // GameTestServer grants operator level 0. The real-client probe covers level >=2.
                check(server.operatorUserPermissionLevel == 0 && !stranger.player.hasPermissions(2))
                val insufficient = OperationSupervisionApi.observe(server, stranger.player, npc.npcUuid)
                check(insufficient.result.code == NpcActionCode.PERMISSION_DENIED && insufficient.observation == null)
                server.playerList.deop(stranger.player.gameProfile)
                stranger.close()
                check(OperationSupervisionApi.observe(server, stranger.player, npc.npcUuid).result.code == NpcActionCode.PERMISSION_DENIED)
                for (bad in listOf(request.copy(taskId = UUID.randomUUID()),
                    request.copy(expiresTick = view.observedTick),
                    request.copy(issuedTick = view.observedTick + 1, expiresTick = view.observedTick + 101))) {
                    check(OperationSupervisionApi.control(server, actor.player, npc.npcUuid, bad).result.status == NpcActionStatus.REJECTED)
                }
                check(TaskCodec.write(record) == before) { "rejected supervision changed durable task" }
                val paused = OperationSupervisionApi.control(server, actor.player, npc.npcUuid, request)
                check(paused.result.status == NpcActionStatus.SUCCEEDED)
                check(paused.observation?.task?.state == OperationTaskState.PAUSED)
                check(record.controlRevision == task.controlRevision + 1)
                check(npc.snapshot().navigation == null)
                val after = TaskCodec.write(record)
                check(OperationSupervisionApi.control(server, actor.player, npc.npcUuid, request).result.code == NpcActionCode.CONFLICT)
                check(TaskCodec.write(record) == after)
                budget = record.primary.remainingTicks; pausedAt = npc.snapshot().position
                val saved = TaskStore.forServer(server).save(CompoundTag())
                check(saved.getInt("version") == 10)
                server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(saved))
                check(TaskCodec.write(checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))) == after)
                wrongThread = CompletableFuture.supplyAsync { OperationSupervisionApi.observe(server, actor.player, npc.npcUuid) }
                stage = 1
            } else if (stage == 1) {
                check(record.status == TaskStatus.PAUSED && record.primary.remainingTicks == budget)
                check(npc.snapshot().navigation == null)
                if (++heldTicks >= 20 && checkNotNull(wrongThread).isDone) {
                    val denied = checkNotNull(wrongThread).join()
                    check(denied.result.code == NpcActionCode.NOT_READY && denied.observation == null)
                    val revision = record.controlRevision
                    // Task commands call this same service; its transition must invalidate old API requests.
                    check(TaskService.resume(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                    check(record.controlRevision == revision + 1 && record.primary.remainingTicks == budget)
                    check(OperationSupervisionApi.control(server, actor.player, npc.npcUuid, checkNotNull(oldRequest)).result.code == NpcActionCode.CONFLICT)
                    check(record.status != TaskStatus.PAUSED)
                    stage = 2
                }
            } else if (stage == 2 && npc.snapshot().navigation != null &&
                TaskNavigator.distanceSquared(npc.snapshot().position, checkNotNull(pausedAt)) > 1) {
                val view = checkNotNull(OperationSupervisionApi.observe(server, actor.player, npc.npcUuid).observation)
                val task = checkNotNull(view.task)
                val cancel = OperationControlRequest(task.taskId, task.controlRevision, task.definitionRevision,
                    view.observedTick, view.observedTick + 100, OperationControl.CANCEL)
                check(OperationSupervisionApi.control(server, actor.player, npc.npcUuid, cancel).result.status == NpcActionStatus.SUCCEEDED)
                check(record.status == TaskStatus.CANCELLED && record.primary.remainingTicks < budget)
                check(npc.snapshot().navigation == null)
                val replacement = OperationAssignmentRequest(record.id, view.observedTick, view.observedTick + 100,
                    OperationOrder.Navigate(view.dimensionId, arena.start))
                check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, replacement).result.status == NpcActionStatus.SUCCEEDED)
                val next = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
                check(OperationSupervisionApi.assign(server, actor.player, npc.npcUuid, replacement).result.code == NpcActionCode.CONFLICT)
                val before = TaskCodec.write(next)
                check(next.id != record.id)
                check(OperationSupervisionApi.control(server, actor.player, npc.npcUuid,
                    cancel.copy(expectedControlRevision = next.controlRevision)).result.code == NpcActionCode.CONFLICT)
                check(TaskCodec.write(next) == before)
                check(OperationSupervisionApi.control(server, actor.player, npc.npcUuid,
                    cancel.copy(taskId = next.id, expectedControlRevision = next.controlRevision)).result.status == NpcActionStatus.SUCCEEDED)
                arena.succeed(npc, next); actor.close()
            }
        }
    }
}
