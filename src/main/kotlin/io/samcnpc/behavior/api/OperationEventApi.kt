package io.samcnpc.behavior.api

import io.samcnpc.behavior.observation.OperationSubscriptions
import io.samcnpc.core.api.NpcActionResult
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

enum class OperationSubscriptionState {
    ACTIVE, UNSUBSCRIBED, AUTHORITY_LOST, NPC_UNAVAILABLE, BODY_CHANGED,
    REGISTRY_RELOADED, SERVER_STOPPED, CALLBACK_FAILED, JOURNAL_INVALID,
}

/** The state remains readable after closure; the token contains no world or listener reference. */
class OperationSubscription internal constructor(val id: UUID, val npcUuid: UUID) {
    @Volatile private var currentState = OperationSubscriptionState.ACTIVE
    val state: OperationSubscriptionState get() = currentState
    internal fun close(reason: OperationSubscriptionState) {
        require(reason != OperationSubscriptionState.ACTIVE)
        if (currentState == OperationSubscriptionState.ACTIVE) currentState = reason
    }
}

fun interface OperationEventListener {
    /** Runs on the server thread. Keep callbacks short; schedule inference outside this callback. */
    fun onEvents(batch: OperationEventBatch)
}

data class OperationSubscriptionReply(val result: NpcActionResult, val subscription: OperationSubscription?)

object OperationEventApi {
    const val MAX_SUBSCRIPTIONS = 128
    const val MAX_PER_NPC = 4
    const val MAX_JOURNAL_ENTRIES = 32

    fun subscribe(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID,
                  listener: OperationEventListener): OperationSubscriptionReply =
        OperationSubscriptions.subscribe(server, actor, npcUuid, listener)

    /** Server-thread only. Possession of the original token permits closing just that subscription. */
    fun unsubscribe(server: MinecraftServer, subscription: OperationSubscription): NpcActionResult =
        OperationSubscriptions.unsubscribe(server, subscription)
}
