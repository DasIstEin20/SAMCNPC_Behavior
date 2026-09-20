package io.samcnpc.behavior.observation

import com.mojang.logging.LogUtils
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Server-thread owned, bounded, opt-in; no retained entity, world, player or facade values. */
internal object OperationSubscriptions {
    private class Member(val token: OperationSubscription, val actorId: UUID,
                         val listener: OperationEventListener, var cursor: Long)
    private class Watch(val generations: OperationGenerations, val journal: OperationEventJournal,
                        val members: MutableMap<UUID, Member> = linkedMapOf())
    private data class Delivery(val member: Member, val batch: OperationEventBatch)
    private val watches = linkedMapOf<UUID, Watch>()
    private val members = linkedMapOf<UUID, Member>()
    private val logger = LogUtils.getLogger()

    fun subscribe(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID,
                  listener: OperationEventListener): OperationSubscriptionReply {
        if (!active(server)) return denied("event subscriptions require the active server thread", NpcActionCode.NOT_READY)
        val captured = OperationEventCapture.read(server, actor, npcUuid)
        val sample = captured.sample ?: return OperationSubscriptionReply(captured.result, null)
        var watch = watches[npcUuid]
        if (watch != null && watch.generations != sample.generations) {
            closeNpc(npcUuid, changed(watch.generations, sample.generations))
            watch = null
        }
        if (members.size >= OperationEventApi.MAX_SUBSCRIPTIONS ||
            (watch?.members?.size ?: 0) >= OperationEventApi.MAX_PER_NPC) {
            return denied("event subscription capacity reached", NpcActionCode.NOT_READY)
        }
        if (watch == null) {
            watch = Watch(sample.generations, OperationEventJournal(UUID.randomUUID(), sample.tick,
                sample.task, sample.completions))
            watches[npcUuid] = watch
        }
        val token = OperationSubscription(UUID.randomUUID(), npcUuid)
        val member = Member(token, actor.uuid, listener, watch.journal.latestSequence)
        watch.members[token.id] = member
        members[token.id] = member
        return OperationSubscriptionReply(NpcActionResult.succeeded("event subscription active"), token)
    }

    fun unsubscribe(server: MinecraftServer, token: OperationSubscription): NpcActionResult {
        if (!server.isSameThread) return NpcActionResult.rejected("unsubscribe requires the server thread", NpcActionCode.NOT_READY)
        if (token.state != OperationSubscriptionState.ACTIVE) return NpcActionResult.succeeded("subscription already closed")
        if (!active(server)) return NpcActionResult.rejected("server session is not active", NpcActionCode.NOT_READY)
        val member = members[token.id]
        if (member?.token !== token) return NpcActionResult.rejected("unknown subscription", NpcActionCode.NOT_FOUND)
        close(member, OperationSubscriptionState.UNSUBSCRIBED)
        return NpcActionResult.succeeded("subscription closed")
    }

    fun read(npcUuid: UUID, generations: OperationGenerations): OperationJournalState {
        val watch = watches[npcUuid] ?: return OperationJournalState.NotRecorded
        if (watch.generations != generations) {
            closeNpc(npcUuid, changed(watch.generations, generations))
            return OperationJournalState.NotRecorded
        }
        return watch.journal.read()
    }

    fun tick(server: MinecraftServer) {
        if (watches.isEmpty() || !active(server)) return
        val deliveries = mutableListOf<Delivery>()
        for ((npcUuid, watch) in watches.toMap()) {
            for (member in watch.members.values.toList()) {
                authorizedActor(server, member)
            }
            val first = watch.members.values.firstOrNull() ?: continue
            val actor = server.playerList.getPlayer(first.actorId) ?: continue
            val captured = OperationEventCapture.read(server, actor, npcUuid)
            val sample = captured.sample
            if (sample == null) {
                closeNpc(npcUuid, if (captured.result.code == NpcActionCode.NOT_FOUND)
                    OperationSubscriptionState.NPC_UNAVAILABLE else OperationSubscriptionState.JOURNAL_INVALID)
                continue
            }
            if (sample.generations != watch.generations) {
                closeNpc(npcUuid, changed(watch.generations, sample.generations))
                continue
            }
            if (!watch.journal.sample(sample.tick, sample.task, sample.completions)) {
                closeNpc(npcUuid, OperationSubscriptionState.JOURNAL_INVALID)
                continue
            }
            for (member in watch.members.values) {
                if (watch.journal.latestSequence <= member.cursor) continue
                val batch = OperationEventBatch(npcUuid, watch.generations, watch.journal.read(member.cursor))
                member.cursor = watch.journal.latestSequence
                deliveries.add(Delivery(member, batch))
            }
        }
        // Callbacks may close another token, reload or unload. Recheck before every invocation.
        for (delivery in deliveries) {
            val member = delivery.member
            if (members[member.token.id] !== member) continue
            val actor = authorizedActor(server, member) ?: continue
            val current = OperationEventCapture.read(server, actor, member.token.npcUuid).sample
            if (current == null || current.generations != delivery.batch.generations) {
                closeNpc(member.token.npcUuid, if (current == null) OperationSubscriptionState.NPC_UNAVAILABLE
                    else changed(delivery.batch.generations, current.generations))
                continue
            }
            try {
                member.listener.onEvents(delivery.batch)
            } catch (failure: Exception) {
                close(member, OperationSubscriptionState.CALLBACK_FAILED)
                logger.warn("Operation event callback closed: token={} exceptionType={}",
                    member.token.id, failure.javaClass.simpleName)
            }
        }
    }

    fun closeActor(actorId: UUID) {
        for (member in members.values.filter { it.actorId == actorId }) close(member, OperationSubscriptionState.AUTHORITY_LOST)
    }

    fun closeNpc(npcUuid: UUID, reason: OperationSubscriptionState) {
        val watch = watches[npcUuid] ?: return
        for (member in watch.members.values.toList()) close(member, reason)
    }

    fun clear(reason: OperationSubscriptionState) {
        for (member in members.values.toList()) close(member, reason)
    }

    private fun authorizedActor(server: MinecraftServer, member: Member): ServerPlayer? {
        val actor = server.playerList.getPlayer(member.actorId)
        val result = actor?.let { OperationEventCapture.authorized(server, it, member.token.npcUuid) }
        if (result?.status == NpcActionStatus.SUCCEEDED) return actor
        close(member, if (result?.code == NpcActionCode.NOT_FOUND) OperationSubscriptionState.NPC_UNAVAILABLE
            else OperationSubscriptionState.AUTHORITY_LOST)
        return null
    }

    private fun close(member: Member, reason: OperationSubscriptionState) {
        if (members[member.token.id] !== member) return
        members.remove(member.token.id)
        val npcUuid = member.token.npcUuid
        val watch = watches[npcUuid]
        watch?.members?.remove(member.token.id)
        if (watch?.members?.isEmpty() == true) watches.remove(npcUuid)
        member.token.close(reason)
    }

    private fun active(server: MinecraftServer) = server.isSameThread && BehaviorRuntimeService.serverOrNull() === server
    private fun changed(before: OperationGenerations, after: OperationGenerations): OperationSubscriptionState = when {
        before.serverSession != after.serverSession -> OperationSubscriptionState.SERVER_STOPPED
        before.registry != after.registry -> OperationSubscriptionState.REGISTRY_RELOADED
        else -> OperationSubscriptionState.BODY_CHANGED
    }
    private fun denied(detail: String, code: NpcActionCode) = OperationSubscriptionReply(NpcActionResult.rejected(detail, code), null)
}
