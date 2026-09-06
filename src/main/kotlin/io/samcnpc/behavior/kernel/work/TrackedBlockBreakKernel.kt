package io.samcnpc.behavior.kernel.work

import io.samcnpc.core.api.NpcActionCompletedEvent
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import java.util.UUID

/**
 * Correlates exactly one long-running Core block-break action per NPC. Task policy decides which
 * block to break and how to recover; this kernel only retains the matching terminal result.
 */
internal class TrackedBlockBreakKernel {
    private val activeActions: MutableMap<UUID, UUID> = mutableMapOf()
    private val completedActions: MutableMap<UUID, NpcActionResult> = mutableMapOf()

    fun onActionCompleted(event: NpcActionCompletedEvent) {
        val expected = activeActions[event.handle.npcUuid] ?: return
        if (event.result.actionId == expected) {
            completedActions[event.handle.npcUuid] = event.result
        }
    }

    fun trackStart(npcUuid: UUID, result: NpcActionResult) {
        if (result.status == NpcActionStatus.ACCEPTED) {
            result.actionId?.let { actionId -> activeActions[npcUuid] = actionId }
        }
    }

    fun takeCompleted(npcUuid: UUID): NpcActionResult? = completedActions.remove(npcUuid)?.also {
        activeActions.remove(npcUuid)
    }

    fun clear(npcUuid: UUID) {
        activeActions.remove(npcUuid)
        completedActions.remove(npcUuid)
    }
}
