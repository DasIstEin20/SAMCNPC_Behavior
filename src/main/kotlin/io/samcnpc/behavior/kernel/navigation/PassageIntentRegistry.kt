package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.core.api.NpcPosition
import java.util.UUID
import kotlin.math.abs

/** Recent declared routes, never paths/entities. Older routes keep priority until leaving the passage. */
internal class PassageIntentRegistry(private val capacity: Int = 4096) {
    data class Intent(val npc: UUID, val task: UUID, val dimension: String, val firstTick: Long,
                      var lastTick: Long, var position: NpcPosition, val destination: NpcPosition)
    private val intents = mutableMapOf<UUID, Intent>()
    private var lastTick: Long? = null
    init { require(capacity in 1..4096) }
    fun update(npc: UUID, task: UUID, dimension: String, now: Long, position: NpcPosition, destination: NpcPosition): Intent? {
        require(now >= 0)
        if (lastTick != null && now < checkNotNull(lastTick)) clear()
        if (lastTick != now) { intents.entries.removeIf { now - it.value.lastTick > 20 }; lastTick = now }
        val old = intents[npc]
        if (old != null && old.task == task && old.dimension == dimension && old.destination == destination) {
            old.lastTick = now; old.position = position; return old
        }
        if (old == null && intents.size >= capacity) return null
        val next = Intent(npc, task, dimension, now, now, position, destination)
        intents[npc] = next
        return next
    }
    fun peer(npc: UUID): Intent? = intents[npc]
    fun yieldsTo(current: Intent, peer: Intent, observedPeer: NpcPosition): Boolean {
        if (current.npc == peer.npc || current.dimension != peer.dimension || abs(current.position.y - observedPeer.y) > 1.25) return false
        if (peer.firstTick > current.firstTick || peer.firstTick == current.firstTick && peer.npc > current.npc) return false
        val dx = observedPeer.x - current.position.x; val dz = observedPeer.z - current.position.z
        if (dx * dx + dz * dz > 3.5 * 3.5) return false
        val ownX = current.destination.x - current.position.x; val ownZ = current.destination.z - current.position.z
        if (ownX * dx + ownZ * dz <= 0.1) return false
        val theirX = peer.destination.x - observedPeer.x; val theirZ = peer.destination.z - observedPeer.z
        val approaching = theirX * -dx + theirZ * -dz > 0.1
        val goalX = current.destination.x - peer.destination.x; val goalZ = current.destination.z - peer.destination.z
        return approaching || goalX * goalX + goalZ * goalZ <= 4.0
    }
    fun release(npc: UUID, task: UUID? = null) { if (task == null || intents[npc]?.task == task) intents.remove(npc) }
    fun clear() { intents.clear(); lastTick = null }
}
