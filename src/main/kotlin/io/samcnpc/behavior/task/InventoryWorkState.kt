package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import java.util.UUID

internal enum class InventoryWorkPhase { WORK, RETURN }
internal enum class InventoryWorkReason {
    SATISFIED, PARTIAL, SOURCE_UNAVAILABLE, DESTINATION_UNAVAILABLE, SOURCE_EMPTY, INVENTORY_FULL, STORAGE_FULL,
    REJECTED, WORK_LIMIT, STEP_LIMIT, TIME_LIMIT, RETURN_FAILED, STATE_MISMATCH, CANCELLED,
    MISSING_TOOL, MISSING_EQUIPMENT, MISSING_RESOURCE,
}
/** Checkpoints contain item counts and facts, never a live path or Core action ID. */
internal class InventoryWorkState(
    val resources: HarvestResources,
    goals: Map<String, Int>,
    val revision: Int,
    var workRemaining: Int,
    var phase: InventoryWorkPhase = InventoryWorkPhase.WORK,
    var steps: Int = 0,
    var reason: InventoryWorkReason? = null,
    var detail: String = "inventory work captured",
    val picked: MutableMap<String, Int> = linkedMapOf(),
    val checkpoints: MutableMap<NpcBlockPosition, ContainerCheckpoint> = linkedMapOf(),
    var selected: NpcBlockPosition? = null,
    var selectedItem: String? = null,
    val deferred: MutableSet<NpcBlockPosition> = linkedSetOf(),
    var lastProblem: InventoryWorkReason? = null,
    val withdrawals: MutableMap<NpcBlockPosition, Map<String, Int>> = linkedMapOf(),
    val deliveries: MutableMap<NpcBlockPosition, Map<String, Int>> = linkedMapOf(),
) {
    // Historical readiness is captured only after the physical return. Active work always reobserves.
    var readiness: InventoryReadiness? = null
    private val admittedGoals = LinkedHashMap(goals)
    val goals: Map<String, Int> = java.util.Collections.unmodifiableMap(admittedGoals)
    var capturedItemIds: List<String> = java.util.List.copyOf(goals.keys.sorted())
        private set
    fun admitPreparationItem(work: EnsureItems, item: String): Boolean {
        if (item in admittedGoals) return true
        if (admittedGoals.size >= 16 || !io.samcnpc.behavior.api.ItemQuery.validItemId(item)) return false
        admittedGoals[item] = work.count
        capturedItemIds = java.util.List.copyOf(admittedGoals.keys.sorted())
        return true
    }
    fun returning(why: InventoryWorkReason, message: String) {
        if (phase == InventoryWorkPhase.RETURN) return
        phase = InventoryWorkPhase.RETURN; reason = why; detail = message.take(TaskRecord.MAX_DETAIL_LENGTH)
        selected = null; selectedItem = null; deferred.clear()
    }
    fun confirmTransfer(observation: io.samcnpc.behavior.kernel.inventory.ContainerTransferObservation, after: Map<String, Int>): String? {
        if (!observation.valid() || observation.itemId !in goals) return "inventory receipt is outside the captured item contract"
        val problem = resources.observeStep(after, mapOf(observation.itemId to observation.containerBefore), mapOf(observation.itemId to observation.containerAfter), false)
        if (problem != null) return problem
        val rows = if (observation.direction == io.samcnpc.behavior.kernel.inventory.ContainerTransferDirection.WITHDRAW) withdrawals else deliveries
        InventoryTransferRows.record(rows,observation.position,observation.itemId,observation.moved)
        return null
    }
    fun satisfied(work: InventoryWork, npc: io.samcnpc.core.api.NpcFacade? = null): Boolean {
        if (work is EnsureItems) return if (npc != null) work.satisfied(npc) else readiness?.let { it.matchingCount >= work.count && (work.destination == null || it.equipmentMatches) } == true
        if (work is UnloadExcess && work.minimumFreeSlots > 0) return if (npc != null) npc.inventoryContents().count { it.stack.isEmpty } >= work.minimumFreeSlots else (readiness?.freeSlots ?: -1) >= work.minimumFreeSlots
        return goals.all { (id, count) -> when (work) {
        is EnsureItems -> false
        is CollectContainer -> moved(work.kind, id) >= count
        is SupplyStock -> moved(work.kind, id) >= count || (resources.retained()[id] ?: 0) >= work.needs.first { it.itemId == id }.target
        is UnloadExcess -> moved(work.kind, id) >= count
        is PickupNearby -> true
    } }
    }
    fun moved(kind: InventoryWorkKind, item: String): Int = when (kind) {
        InventoryWorkKind.SUPPLY, InventoryWorkKind.COLLECT, InventoryWorkKind.ENSURE -> resources.entries[item]?.supplied ?: 0
        InventoryWorkKind.UNLOAD -> resources.entries[item]?.delivered ?: 0
        InventoryWorkKind.PICKUP -> picked[item] ?: 0
    }
    fun outcome(frameId: UUID, kind: InventoryWorkKind, returned: Boolean, why: InventoryWorkReason? = null) = InventoryWorkOutcome(
        frameId, kind, revision, why ?: reason ?: InventoryWorkReason.CANCELLED, returned, goals,
        resources.entries.filterValues { it.supplied > 0 }.mapValues { it.value.supplied },
        resources.entries.filterValues { it.delivered > 0 }.mapValues { it.value.delivered },
        java.util.Map.copyOf(picked), steps, detail, java.util.Map.copyOf(withdrawals), java.util.Map.copyOf(deliveries))
}
internal data class InventoryReadiness(val matchingCount: Int, val equipmentMatches: Boolean, val freeSlots: Int)
internal data class InventoryWorkOutcome(
    val frameId: UUID, val kind: InventoryWorkKind, val revision: Int,
    val reason: InventoryWorkReason, val returned: Boolean,
    val goals: Map<String, Int>, val supplied: Map<String, Int>, val unloaded: Map<String, Int>,
    val picked: Map<String, Int>, val steps: Int, val detail: String,
    val sources: Map<NpcBlockPosition, Map<String, Int>> = emptyMap(),
    val recipients: Map<NpcBlockPosition, Map<String, Int>> = emptyMap(),
)
