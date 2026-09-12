package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcEquipmentSnapshot
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcInventoryLoadSnapshot
import io.samcnpc.core.api.NpcItemStackSnapshot
import java.util.UUID

/** Gross physical counts. Recovered scaffold drops are gathered again; placement stays consumed. */
internal data class HarvestResource(
    val initial: Int = 0,
    var gathered: Int = 0,
    var supplied: Int = 0,
    var consumed: Int = 0,
    var delivered: Int = 0,
    var lost: Int = 0,
    var retained: Int = initial,
) {
    fun valid(): Boolean = listOf(initial, gathered, supplied, consumed, delivered, lost, retained).all { it in 0..MAX_COUNT } &&
        initial.toLong() + gathered + supplied == retained.toLong() + consumed + delivered + lost
    companion object { const val MAX_COUNT = 1_000_000 }
}

internal class HarvestResources(initial: Map<String, Int>) {
    private val amounts = initial.mapValuesTo(linkedMapOf()) { HarvestResource(initial = it.value) }
    var observedLoadGeneration: UUID? = null
    var mustReconcileLoad: Boolean = true
    var uncertain: Boolean = false
    val entries: Map<String, HarvestResource> get() = amounts.mapValues { it.value.copy() }
    fun retained(): Map<String, Int> = amounts.filterValues { it.retained > 0 }.mapValues { it.value.retained }
    fun delivered(wood: WoodSelection): Int = amounts.filterKeys(wood::matches).values.sumOf { it.delivered - it.supplied }.coerceAtLeast(0)
    fun potentialDelivery(wood: WoodSelection, excessCarried: Int): Int =
        (amounts.filterKeys(wood::matches).values.sumOf { it.delivered - it.supplied } + excessCarried).coerceAtLeast(0)

    fun reconcileLoad(loaded: NpcInventoryLoadSnapshot?, current: Map<String, Int>): String? {
        if (!mustReconcileLoad && (loaded == null || loaded.generation == observedLoadGeneration)) return null
        val actual = if (loaded == null) current else inventoryCounts(loaded)
        if (actual != retained()) {
            uncertain = true
            return "loaded inventory differs from the resource checkpoint; no work replayed"
        }
        mustReconcileLoad = false
        observedLoadGeneration = loaded?.generation
        return null
    }

    /** Passive pickup/other live changes are observations; a loss is not attributed to a use action. */
    fun observeLive(current: Map<String, Int>): String? = update(current) { id, counter ->
        val delta = (current[id] ?: 0) - counter.retained
        if (delta > 0) counter.gathered += delta else counter.lost -= delta
    }

    /** One selected step, measured on both sides of the explicit container. */
    fun observeStep(after: Map<String, Int>, containerBefore: Map<String, Int>, containerAfter: Map<String, Int>, placement: Boolean): String? {
        val before = retained()
        for (id in containerBefore.keys + containerAfter.keys) {
            val delta = (containerAfter[id] ?: 0) - (containerBefore[id] ?: 0)
            if (delta != 0 && delta != (before[id] ?: 0) - (after[id] ?: 0)) {
                uncertain = true
                return "unmatched container/resource delta for $id"
            }
        }
        return update(after) { id, counter ->
            val delta = (after[id] ?: 0) - counter.retained
            val stored = (containerAfter[id] ?: 0) - (containerBefore[id] ?: 0)
            when {
                delta < 0 && stored == -delta -> counter.delivered -= delta
                delta > 0 && stored == -delta -> counter.supplied += delta
                stored != 0 -> throw IllegalArgumentException("unmatched container/resource delta for $id")
                delta < 0 && placement -> counter.consumed -= delta
                delta < 0 -> counter.lost -= delta
                delta > 0 -> counter.gathered += delta
            }
        }
    }

    /** The primitive's actual receipt is authoritative even when a machine processes during insertion. */
    fun observeTransfer(after: Map<String, Int>, itemId: String, moved: Int, inserting: Boolean): String? {
        val expected = if (inserting) -moved else moved
        if (uncertain || moved !in 1..64 || (after[itemId] ?: 0) - (retained()[itemId] ?: 0) != expected) {
            uncertain = true
            return "actual transfer does not match the NPC resource delta"
        }
        return update(after) { id, counter ->
            val delta = (after[id] ?: 0) - counter.retained
            if (id == itemId) {
                if (inserting) counter.delivered += moved else counter.supplied += moved
            } else if (delta > 0) counter.gathered += delta else counter.lost -= delta
        }
    }

    private fun update(current: Map<String, Int>, classify: (String, HarvestResource) -> Unit): String? {
        if (current == retained()) return null
        if (!validCounts(current) || (amounts.keys + current.keys).size > MAX_KINDS) {
            uncertain = true
            return "resource observation exceeds bounded accounting"
        }
        val next = amounts.mapValuesTo(linkedMapOf()) { it.value.copy() }
        try {
            for (id in (amounts.keys + current.keys).sorted()) {
                val counter = next.getOrPut(id) { HarvestResource() }
                classify(id, counter)
                counter.retained = current[id] ?: 0
                require(counter.valid()) { "resource counters exceed their bounds for $id" }
            }
        } catch (error: IllegalArgumentException) {
            uncertain = true
            return error.message ?: "invalid resource observation"
        }
        amounts.clear(); amounts.putAll(next)
        return null
    }

    fun describe(wood: WoodSelection, quantity: Int): String {
        val totals = HarvestResource()
        for ((id, resource) in amounts) if (wood.matches(id)) {
            totals.gathered += resource.gathered; totals.supplied += resource.supplied
            totals.consumed += resource.consumed; totals.delivered += resource.delivered
            totals.lost += resource.lost; totals.retained += resource.retained
        }
        return "gathered=${totals.gathered}; supplied=${totals.supplied}; consumed=${totals.consumed}; " +
            "retained=${totals.retained}; deliveredGross=${totals.delivered}; deliveredNet=${delivered(wood)}; lost=${totals.lost}; " +
            "shortage=${(quantity - delivered(wood)).coerceAtLeast(0)}; excess=${(delivered(wood) - quantity).coerceAtLeast(0)}; uncertain=$uncertain"
    }

    companion object {
        const val MAX_KINDS = 64
        private val ID = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
        fun validCounts(counts: Map<String, Int>): Boolean = counts.size <= MAX_KINDS && counts.all { (id, count) ->
            id.length <= 256 && ID.matches(id) && count in 1..HarvestResource.MAX_COUNT
        }
        fun restore(entries: Map<String, HarvestResource>, uncertain: Boolean): HarvestResources {
            require(entries.size <= MAX_KINDS && entries.all { (id, value) -> id.length <= 256 && ID.matches(id) && value.valid() }) { "invalid harvest resources" }
            val progress = HarvestResources(emptyMap())
            for ((id, value) in entries) progress.amounts[id] = value.copy()
            progress.uncertain = uncertain
            return progress
        }
        fun capture(npc: NpcFacade): HarvestResources {
            val counts = inventoryCounts(npc)
            require(validCounts(counts)) { "inventory exceeds resource accounting bounds" }
            val progress = HarvestResources(counts)
            progress.observedLoadGeneration = npc.inventoryLoadSnapshot()?.generation
            progress.mustReconcileLoad = false
            return progress
        }
        fun inventoryCounts(npc: NpcFacade): Map<String, Int> = counts(npc.inventoryContents().map { it.stack }, npc.equipmentContents())
        fun inventoryCounts(loaded: NpcInventoryLoadSnapshot): Map<String, Int> = counts(loaded.inventory, loaded.equipment)
        private fun counts(inventory: List<NpcItemStackSnapshot>, equipment: NpcEquipmentSnapshot): Map<String, Int> {
            val counts = linkedMapOf<String, Int>()
            // Main hand is an alias in inventory, while these seven stores are separate.
            val separate = listOf(equipment.offHand, equipment.head, equipment.chest, equipment.legs, equipment.feet, equipment.ammunition, equipment.totem)
            for (stack in inventory + separate) {
                val id = stack.itemId ?: continue
                if (stack.count > 0) counts[id] = (counts[id] ?: 0) + stack.count
            }
            return counts
        }
    }
}
