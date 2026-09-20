package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.observation.OperationSubscriptions
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.TaskService
import io.samcnpc.core.api.*
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object OperationEventGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 200, batch = "operation_events")
    fun sampledControlsAreBoundedAuthorizedAndCallbackClosurePreventsQueuedDelivery(helper: GameTestHelper) {
        val server = helper.level.server
        val actor = GameTestActor(helper.level, "EventSam")
        val stranger = GameTestActor(helper.level, "EventOther")
        val arena = CombatGameTestArena(helper, actor.player)
        arena.onReady { npc ->
            try {
                val id = npc.npcUuid
                check(checkNotNull(OperationInspectionApi.inspect(server, actor.player, id).inspection).journal ==
                    OperationJournalState.NotRecorded)
                val observation = checkNotNull(OperationSupervisionApi.observe(server, actor.player, id).observation)
                val assigned = OperationSupervisionApi.assign(server, actor.player, id, OperationAssignmentRequest(null,
                    observation.observedTick, observation.observedTick + 100,
                    OperationOrder.Navigate(observation.dimensionId, arena.start.copy(x = arena.start.x + 20))))
                check(assigned.result.status == NpcActionStatus.SUCCEEDED)
                check(TaskService.pause(server, id).status == NpcActionStatus.SUCCEEDED)
                val denied = OperationEventApi.subscribe(server, stranger.player, id) { error("unauthorized callback") }
                check(denied.result.code == NpcActionCode.PERMISSION_DENIED && denied.subscription == null)
                val offThread = CompletableFuture.supplyAsync {
                    OperationEventApi.subscribe(server, actor.player, id) { error("off-thread callback") }
                }.get(5, TimeUnit.SECONDS)
                check(offThread.result.code == NpcActionCode.NOT_READY && offThread.subscription == null)
                val batches = mutableListOf<OperationEventBatch>()
                var skipped: OperationSubscription? = null
                var fourthCalls = 0
                val first = checkNotNull(OperationEventApi.subscribe(server, actor.player, id) {
                    batches.add(it)
                    check(OperationEventApi.unsubscribe(server, checkNotNull(skipped)).status == NpcActionStatus.SUCCEEDED)
                }.subscription)
                val failed = checkNotNull(OperationEventApi.subscribe(server, actor.player, id) {
                    throw IllegalStateException("PRIVATE_CALLBACK_MESSAGE_MUST_NOT_BE_LOGGED")
                }.subscription)
                skipped = checkNotNull(OperationEventApi.subscribe(server, actor.player, id) { error("closed callback executed") }.subscription)
                val fourth = checkNotNull(OperationEventApi.subscribe(server, actor.player, id) { fourthCalls++ }.subscription)
                check(OperationEventApi.subscribe(server, actor.player, id) {}.subscription == null)
                check(TaskService.resume(server, id).status == NpcActionStatus.SUCCEEDED)
                check(TaskService.pause(server, id).status == NpcActionStatus.SUCCEEDED)
                OperationSubscriptions.tick(server)
                check(batches.size == 1 && fourthCalls == 1)
                val control = batches.single().journal.events.single { it.kind == OperationEventKind.CONTROL_CHANGED }
                check(control.coalesced && !control.kind.decisionBoundary)
                check(failed.state == OperationSubscriptionState.CALLBACK_FAILED)
                check(checkNotNull(skipped).state == OperationSubscriptionState.UNSUBSCRIBED)
                OperationSubscriptions.tick(server)
                check(batches.size == 1 && fourthCalls == 1)
                val journal = checkNotNull(OperationInspectionApi.inspect(server, actor.player, id).inspection).journal
                check(journal is OperationJournalState.Recorded && journal.events.contains(control))
                actor.player.teleportTo(helper.level, arena.start.x + 300, arena.start.y, arena.start.z, 0F, 0F)
                OperationSubscriptions.tick(server)
                check(first.state == OperationSubscriptionState.AUTHORITY_LOST && fourth.state == OperationSubscriptionState.AUTHORITY_LOST)
                check(batches.size == 1 && fourthCalls == 1)
            } finally { arena.close(); actor.close(); stranger.close() }
            helper.succeed()
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 160, batch = "operation_events_lifecycle")
    fun loadReloadAndUnloadCloseTokensWithoutCallbacks(helper: GameTestHelper) {
        val server = helper.level.server
        val actor = GameTestActor(helper.level, "EventLife")
        val arena = CombatGameTestArena(helper, actor.player)
        arena.onReady { npc ->
            try {
                fun subscribe() = checkNotNull(OperationEventApi.subscribe(server, actor.player, npc.npcUuid) {
                    error("invalidated callback executed")
                }.subscription)
                val first = subscribe()
                arena.body.load(arena.body.saveWithoutId(CompoundTag()))
                OperationSubscriptions.tick(server)
                check(first.state == OperationSubscriptionState.BODY_CHANGED)
                val second = subscribe()
                BehaviorRuntimeService.reload()
                check(second.state == OperationSubscriptionState.REGISTRY_RELOADED)
                val third = subscribe()
                arena.body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK)
                check(third.state == OperationSubscriptionState.NPC_UNAVAILABLE)
                OperationSubscriptions.tick(server)
                check(OperationEventApi.unsubscribe(server, third).status == NpcActionStatus.SUCCEEDED)
            } finally { arena.close(); actor.close() }
            helper.succeed()
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 160, batch = "operation_events_logout")
    fun disconnectedActorCannotReceiveEventsAndHistoryStopsWhenUnwatched(helper: GameTestHelper) {
        val server = helper.level.server
        val actor = GameTestActor(helper.level, "EventLogout")
        val arena = CombatGameTestArena(helper, actor.player)
        var disconnected = false
        arena.onReady { npc ->
            try {
                val token = checkNotNull(OperationEventApi.subscribe(server, actor.player, npc.npcUuid) {
                    error("disconnected actor callback")
                }.subscription)
                actor.close(); disconnected = true
                OperationSubscriptions.tick(server)
                check(token.state == OperationSubscriptionState.AUTHORITY_LOST)
                val rejected = OperationEventApi.subscribe(server, actor.player, npc.npcUuid) {}
                check(rejected.result.code == NpcActionCode.PERMISSION_DENIED && rejected.subscription == null)
            } finally { arena.close(); if (!disconnected) actor.close() }
            helper.succeed()
        }
    }
}
