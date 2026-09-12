package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.ContainerTransferDirection
import io.samcnpc.behavior.kernel.inventory.ContainerTransferObservation
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcInventoryLoadSnapshot

/** Origin of physically retained stock; supplied seeds/rations cannot satisfy a harvest quota. */
internal data class ProducedResourceOrigin(
    var stock: Int = 0,
    var output: Int = 0,
    var sourceOutput: Int = 0,
    var deliveredOutput: Int = 0,
    var protectedGathered: Int = 0,
)

internal enum class ProducedGain { OUTPUT, STOCK }

internal enum class ProducedTransfer { SUPPLY, OUTPUT_SOURCE, DELIVERY, AUXILIARY_UNLOAD }

/** Conservation is shared with wood/inventory work; this small layer adds explicit quota provenance. */
internal class ProducedResources private constructor(
    val physical: HarvestResources,
    private val origins: MutableMap<String, ProducedResourceOrigin>,
) {
    val entries: Map<String, ProducedResourceOrigin> get() = origins.mapValues { it.value.copy() }
    fun available(item: String): Int = origins[item]?.output ?: 0
    fun delivered(item: String): Int = origins[item]?.deliveredOutput ?: 0
    fun reconcileLoad(loaded: NpcInventoryLoadSnapshot?, current: Map<String,Int>): String? =
        physical.reconcileLoad(loaded,current)

    fun observeLive(current: Map<String,Int>,origin: ProducedGain = ProducedGain.OUTPUT): String? = change(current, null, null, false,origin)

    /** Only an actual Core pickup completion can turn this exact gain into produced output. */
    fun pickup(item: String, count: Int, current: Map<String,Int>): String? {
        if (count !in 1..2304 || (current[item] ?: 0) - (physical.retained()[item] ?: 0) != count)
            return invalid("pickup credit differs from the actual insertion")
        return change(current, null, null, false, ProducedGain.STOCK, item)
    }

    fun consume(item: String, count: Int, current: Map<String,Int>, gain: ProducedGain = ProducedGain.OUTPUT): String? {
        val before = physical.retained()
        if (count !in 1..2304 || (before[item] ?: 0) - (current[item] ?: 0) != count ||
            before.any { (id, amount) -> id != item && (current[id] ?: 0) < amount }) return invalid("consumption does not match the actual selected item delta")
        return change(current, null, null, true,gain)
    }

    fun transfer(observation: ContainerTransferObservation, role: ProducedTransfer, current: Map<String,Int>, gain: ProducedGain = ProducedGain.OUTPUT): String? {
        if (!observation.valid()) return invalid("invalid physical transfer observation")
        val withdraw = observation.direction == ContainerTransferDirection.WITHDRAW
        if (withdraw != (role == ProducedTransfer.SUPPLY || role == ProducedTransfer.OUTPUT_SOURCE)) return invalid("transfer role and direction differ")
        val before = physical.retained()[observation.itemId] ?: 0
        if ((current[observation.itemId] ?: 0) - before != if (withdraw) observation.moved else -observation.moved) return invalid("physical transfer and retained stock differ")
        if (role == ProducedTransfer.DELIVERY && available(observation.itemId) < observation.moved) return invalid("delivery attempted to credit protected stock")
        return change(current,observation,role,false,gain)
    }

    private fun change(current: Map<String,Int>, transfer: ContainerTransferObservation?, role: ProducedTransfer?, consume: Boolean, gain: ProducedGain = ProducedGain.OUTPUT, pickupItem: String? = null): String? {
        if (physical.uncertain) return "resource provenance is already uncertain"
        val before = physical.retained()
        if (!HarvestResources.validCounts(current)) return invalid("resource provenance counts exceed bounds")
        val keys = origins.keys + current.keys
        if (keys.size > HarvestResources.MAX_KINDS) return invalid("resource provenance item kinds exceed bounds")
        val next = origins.mapValuesTo(linkedMapOf()) { it.value.copy() }
        for (id in keys) {
            val entry = next.getOrPut(id) { ProducedResourceOrigin() }
            val delta = (current[id] ?: 0) - (before[id] ?: 0)
            if (transfer?.itemId == id) {
                when (role) {
                    ProducedTransfer.SUPPLY -> entry.stock += delta
                    ProducedTransfer.OUTPUT_SOURCE -> { entry.output += delta; entry.sourceOutput += delta }
                    ProducedTransfer.DELIVERY -> { entry.output += delta; entry.deliveredOutput -= delta }
                    ProducedTransfer.AUXILIARY_UNLOAD -> remove(entry,-delta,protectedFirst=true)
                    null -> return invalid("missing transfer role")
                }
            } else if (delta > 0) {
                if (gain == ProducedGain.STOCK && id != pickupItem) { entry.stock += delta; entry.protectedGathered += delta }
                else entry.output += delta
            }
            else if (delta < 0) remove(entry,-delta,protectedFirst=consume)
            if (listOf(entry.stock,entry.output,entry.sourceOutput,entry.deliveredOutput,entry.protectedGathered).any { it !in 0..HarvestResource.MAX_COUNT } ||
                entry.stock.toLong()+entry.output != (current[id] ?: 0).toLong()) return invalid("resource provenance is inconsistent for $id")
        }
        val problem = if (transfer == null && !consume) physical.observeLive(current) else physical.observeStep(current,
            if (transfer == null) emptyMap() else mapOf(transfer.itemId to transfer.containerBefore),
            if (transfer == null) emptyMap() else mapOf(transfer.itemId to transfer.containerAfter),placement=consume)
        if (problem != null) return problem
        origins.clear(); origins.putAll(next)
        return null
    }
    private fun remove(entry: ProducedResourceOrigin, count: Int, protectedFirst: Boolean) {
        if (protectedFirst) {
            val stock = minOf(count,entry.stock); entry.stock -= stock; entry.output -= count-stock
        } else {
            val output = minOf(count,entry.output); entry.output -= output; entry.stock -= count-output
        }
    }
    private fun invalid(message: String): String { physical.uncertain=true; return message }

    companion object {
        fun capture(npc: NpcFacade): ProducedResources {
            val physical=HarvestResources.capture(npc)
            return ProducedResources(physical,physical.retained().mapValuesTo(linkedMapOf()) { ProducedResourceOrigin(stock=it.value) })
        }
        fun initial(counts: Map<String,Int>): ProducedResources {
            require(HarvestResources.validCounts(counts))
            return ProducedResources(HarvestResources(counts),counts.mapValuesTo(linkedMapOf()) { ProducedResourceOrigin(stock=it.value) })
        }
        fun restore(physical: HarvestResources, origins: Map<String,ProducedResourceOrigin>): ProducedResources {
            val rows=physical.entries
            require(origins.keys == rows.keys) { "resource origin keys differ from physical history" }
            for ((id, origin) in origins) {
                val row=rows.getValue(id)
                require(listOf(origin.stock,origin.output,origin.sourceOutput,origin.deliveredOutput,origin.protectedGathered).all { it in 0..HarvestResource.MAX_COUNT })
                require(origin.stock.toLong()+origin.output == row.retained.toLong()) { "retained origin counts differ" }
                require(origin.protectedGathered <= row.gathered) { "protected gain credit exceeds actual collection" }
                require(origin.sourceOutput <= row.supplied && origin.deliveredOutput <= row.delivered) { "origin credit exceeds physical transfer" }
                require(origin.output.toLong()+origin.deliveredOutput <= row.gathered.toLong()-origin.protectedGathered+origin.sourceOutput) { "output credit exceeds collected/authorized cargo" }
                require(origin.stock.toLong() <= row.initial.toLong()+row.supplied-origin.sourceOutput+origin.protectedGathered) { "protected credit exceeds original/auxiliary stock" }
            }
            return ProducedResources(physical,origins.mapValuesTo(linkedMapOf()) { it.value.copy() })
        }
    }
}
