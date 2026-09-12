package io.samcnpc.behavior.combat

import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import java.util.UUID

/** Decisions only: observations are explicit test input, never evidence of real Minecraft effects. */
internal class CombatPolicyWorld : NpcWorldView {
    val entities = linkedMapOf<UUID, NpcEntityObservation>()
    val tags = mutableMapOf<UUID, Set<String>>()
    override val dimensionId = "minecraft:overworld"
    override fun observeEntity(uuid: UUID) = entities[uuid]
    override fun observeEntity(uuid: UUID, filter: NpcEntityTypeFilter) = entities[uuid]?.takeIf { matches(it, filter) }
    override fun queryEntities(query: NpcEntityQuery) = entities.values.filter {
        (query.typeIds.isEmpty() || it.typeId in query.typeIds) && (query.typeFilter == null || matches(it, checkNotNull(query.typeFilter)))
    }.take(query.limit)
    private fun matches(entity: NpcEntityObservation, filter: NpcEntityTypeFilter) = filter.isEmpty || entity.typeId in filter.typeIds || tags[entity.uuid].orEmpty().any { it in filter.tagIds }
    override fun observeBlock(position: NpcBlockPosition) = error("unexpected block query")
    override fun observeBlockContainer(position: NpcBlockPosition) = error("unexpected container query")
    override fun raycast(request: NpcRaycastRequest) = error("unexpected raycast")
    fun enemy(id: Long, x: Double = 1.5, type: String = "minecraft:zombie"): NpcEntityObservation {
        val entity = NpcEntityObservation(UUID(0, id), type, NpcPosition(x, 64.0, 0.0), NpcVector(0.0, 0.0, 0.0), true, false, 1.0,
            combat = NpcEntityCombatObservation(true, true, false))
        entities[entity.uuid] = entity
        return entity
    }
}
internal class CombatPolicyBody(val world: CombatPolicyWorld) : TestNpcFacade() {
    var current = decisionContext(health = 1.0).snapshot
    var hits = 0
    var inventoryQueries = 0
    var inventories = emptyList<NpcInventoryEntry>()
    val routes = mutableListOf<NpcNavigationRequest>()
    var uses = 0
    override fun snapshot() = current
    override fun worldView() = world
    override fun inventoryContents(): List<NpcInventoryEntry> { inventoryQueries++; return inventories }
    override fun lookAtEntity(entityUuid: UUID) = NpcActionResult.succeeded("test look")
    override fun attackEntity(entityUuid: UUID): NpcActionResult { hits++; return NpcActionResult.succeeded("submitted attack, no world effects simulated") }
    override fun navigateTo(request: NpcNavigationRequest): NpcActionResult { routes.add(request); return NpcActionResult.accepted("test route", UUID.randomUUID()) }
    override fun stopControl() = NpcActionResult.succeeded("test stop")
    override fun continueItemUse(): NpcActionResult { uses++; return NpcActionResult.running("renewed same use") }
}
