package io.samcnpc.behavior.combat

import io.samcnpc.behavior.task.TaskNavigator
import io.samcnpc.core.api.*
import java.util.UUID

/** Pure eligibility shared by exact-UUID selection and deterministic bounded search. */
internal object CombatTargetSelector {
    data class Area(val center: NpcPosition, val radius: Double) {
        init {
            require(center.x.isFinite() && center.y.isFinite() && center.z.isFinite())
            require(radius.isFinite() && radius in 1.0..32.0)
        }
        fun contains(position: NpcPosition): Boolean = TaskNavigator.distanceSquared(center, position) <= radius * radius
    }

    fun eligible(snapshot: NpcSnapshot, target: NpcEntityObservation, area: Area, allowPlayers: Boolean = false,
                 requireVisible: Boolean = true): Boolean {
        val facts = target.combat ?: return false
        if (!target.alive || !facts.permitted || facts.allied || (requireVisible && !facts.visible)) return false
        if (target.uuid == snapshot.npcUuid || target.uuid == snapshot.summonerUuid) return false
        if (snapshot.summonerUuid != null && facts.summonerUuid == snapshot.summonerUuid) return false
        if (target.isPlayer && !allowPlayers) return false
        return area.contains(snapshot.position) && area.contains(target.position)
    }

    /** A missing specified UUID never falls back to another entity. */
    fun exact(snapshot: NpcSnapshot, world: NpcWorldView, uuid: UUID, area: Area, allowPlayers: Boolean = false, requireVisible: Boolean = true): NpcEntityObservation? {
        val target = world.observeEntity(uuid) ?: return null
        return target.takeIf { eligible(snapshot, it, area, allowPlayers, requireVisible) }
    }

    fun nearest(snapshot: NpcSnapshot, world: NpcWorldView, area: Area, typeIds: Set<String>, retained: UUID? = null,
                allowPlayers: Boolean = false): NpcEntityObservation? {
        require(typeIds.isNotEmpty() && typeIds.size <= 32) { "nearest combat search requires a bounded explicit type filter" }
        if (!area.contains(snapshot.position)) return null
        if (retained != null) {
            val previous = exact(snapshot, world, retained, area, allowPlayers)
            if (previous != null && previous.typeId in typeIds) return previous
        }
        return world.queryEntities(NpcEntityQuery(area.center, area.radius, 64, typeIds = typeIds))
            .filter { it.typeId in typeIds && eligible(snapshot, it, area, allowPlayers) }
            .minWithOrNull(compareBy<NpcEntityObservation> { TaskNavigator.distanceSquared(snapshot.position, it.position) }.thenBy { it.uuid.toString() })
    }
    fun filtered(snapshot: NpcSnapshot, world: NpcWorldView, area: Area, filter: NpcEntityTypeFilter,
                 retained: UUID? = null, allowPlayers: Boolean = false, excluded: Set<UUID> = emptySet()): NpcEntityObservation? {
        require(!filter.isEmpty) { "area combat requires an explicit type/tag filter" }
        if (!area.contains(snapshot.position)) return null
        if (retained != null && retained !in excluded) {
            val target = world.observeEntity(retained, filter)
            if (target != null && eligible(snapshot, target, area, allowPlayers)) return target
        }
        return world.queryEntities(NpcEntityQuery(area.center, area.radius, 64, typeFilter = filter))
            .filter { it.uuid !in excluded && eligible(snapshot, it, area, allowPlayers) }
            .minWithOrNull(compareBy<NpcEntityObservation> { TaskNavigator.distanceSquared(snapshot.position, it.position) }.thenBy { it.uuid.toString() })
    }

}
