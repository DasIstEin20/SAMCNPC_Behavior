package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import java.util.UUID

internal enum class InventoryWorkPhase { WORK, RETURN }
internal enum class InventoryWorkReason {
    SATISFIED, PARTIAL, SOURCE_UNAVAILABLE, DESTINATION_UNAVAILABLE, SOURCE_EMPTY, INVENTORY_FULL, STORAGE_FULL,
    REJECTED, WORK_LIMIT, STEP_LIMIT, TIME_LIMIT, RETURN_FAILED, STATE_MISMATCH, CANCELLED,
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
    val goals: Map<String, Int> = java.util.Map.copyOf(goals)
    val capturedItemIds: List<String> = java.util.List.copyOf(goals.keys.sorted())
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
    fun satisfied(work: InventoryWork): Boolean = goals.all { (id, count) -> when (work) {
        is CollectContainer -> moved(work.kind, id) >= count
        is SupplyStock -> moved(work.kind, id) >= count || (resources.retained()[id] ?: 0) >= work.needs.first { it.itemId == id }.target
        is UnloadExcess -> moved(work.kind, id) >= count
        is PickupNearby -> true
    } }
    fun moved(kind: InventoryWorkKind, item: String): Int = when (kind) {
        InventoryWorkKind.SUPPLY, InventoryWorkKind.COLLECT -> resources.entries[item]?.supplied ?: 0
        InventoryWorkKind.UNLOAD -> resources.entries[item]?.delivered ?: 0
        InventoryWorkKind.PICKUP -> picked[item] ?: 0
    }
    fun outcome(frameId: UUID, kind: InventoryWorkKind, returned: Boolean, why: InventoryWorkReason? = null) = InventoryWorkOutcome(
        frameId, kind, revision, why ?: reason ?: InventoryWorkReason.CANCELLED, returned, goals,
        resources.entries.filterValues { it.supplied > 0 }.mapValues { it.value.supplied },
        resources.entries.filterValues { it.delivered > 0 }.mapValues { it.value.delivered },
        java.util.Map.copyOf(picked), steps, detail, java.util.Map.copyOf(withdrawals), java.util.Map.copyOf(deliveries))
}
internal data class InventoryWorkOutcome(
    val frameId: UUID, val kind: InventoryWorkKind, val revision: Int,
    val reason: InventoryWorkReason, val returned: Boolean,
    val goals: Map<String, Int>, val supplied: Map<String, Int>, val unloaded: Map<String, Int>,
    val picked: Map<String, Int>, val steps: Int, val detail: String,
    val sources: Map<NpcBlockPosition, Map<String, Int>> = emptyMap(),
    val recipients: Map<NpcBlockPosition, Map<String, Int>> = emptyMap(),
)
