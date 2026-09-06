package io.samcnpc.behavior.runtime

import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcEntityObservation
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcWorldView
import java.util.UUID

/**
 * Logical target selection is behavior runtime state. Core supplies only an immutable recent-damage
 * observation and executes an action against the explicit UUID selected here.
 */
object BehaviorTargetMemory {
    private val targetsByNpc: MutableMap<UUID, UUID> = mutableMapOf()

    fun acquireFromRecentDamage(npc: NpcFacade): NpcActionResult {
        val source = npc.snapshot().lastDamageSourceEntityUuid
            ?: return NpcActionResult.rejected("no recent damage source entity")
        targetsByNpc[npc.npcUuid] = source
        return NpcActionResult.succeeded("behavior target acquired from recent damage source")
    }

    fun targetFor(npcUuid: UUID): UUID? = targetsByNpc[npcUuid]

    fun clear(npcUuid: UUID): NpcActionResult {
        targetsByNpc.remove(npcUuid)
        return NpcActionResult.succeeded("behavior target cleared")
    }

    fun resolveLiveTarget(npc: NpcFacade, world: NpcWorldView): NpcEntityObservation? {
        val targetUuid = targetsByNpc[npc.npcUuid] ?: return null
        val target = world.observeEntity(targetUuid)
        if (target == null || !target.alive) {
            targetsByNpc.remove(npc.npcUuid)
            return null
        }
        return target
    }

    fun hasLiveTarget(npc: NpcFacade, world: NpcWorldView): Boolean = resolveLiveTarget(npc, world) != null

    fun remove(npcUuid: UUID) {
        targetsByNpc.remove(npcUuid)
    }

    fun clearAll() {
        targetsByNpc.clear()
    }
}
