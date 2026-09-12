package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcItemStackSnapshot

internal enum class MachinePhase { APPROACH, WORK, RETURN, DONE }
internal data class MachinePortShape(val blockId: String, val slots: Int)
internal class MachineTaskState(
    val resources: HarvestResources,
    val supplied: IntArray,
    shapes: List<MachinePortShape>,
    var collected: Int = 0,
    var cursor: Int = 0,
    var pollRemaining: Int = 0,
    var idleTicks: Int = 0,
    var phase: MachinePhase = MachinePhase.APPROACH,
    var transfers: Int = 0,
) {
    val shapes: List<MachinePortShape> = java.util.List.copyOf(shapes)
    // A new execution observes without resetting the persisted no-progress clock.
    var observedSlots: List<NpcItemStackSnapshot>? = null
    fun goal(d: MachineTaskDefinition): Boolean = supplied.indices.all { supplied[it] == d.feeds.ports[it].quantity } && collected == d.output.quantity
    fun validationProblem(d: MachineTaskDefinition): String? {
        if (supplied.size != d.feeds.ports.size || shapes.size != d.ports.size) return "machine state shape differs from definition"
        if (supplied.indices.any { supplied[it] !in 0..d.feeds.ports[it].quantity } || collected !in 0..d.output.quantity) return "machine quantity counters exceed the contract"
        if (cursor !in d.ports.indices || pollRemaining !in 0..d.pollTicks || idleTicks !in 0..d.budget.ticks || transfers !in 0..4608) return "machine clocks/cursor/transfer count exceed bounds"
        if (shapes.indices.any { shapes[it].blockId.isBlank() || shapes[it].blockId.length > 256 || shapes[it].slots <= d.ports[it].slot }) return "machine port checkpoint is invalid"
        val counts = resources.entries
        val delivered = d.feeds.ports.indices.groupBy { d.feeds.ports[it].itemId }.mapValues { (_,indexes) -> indexes.sumOf { supplied[it] } }
        if (delivered.any { (id,count) -> (counts[id]?.delivered ?: 0) != count } || (counts[d.output.itemId]?.supplied ?: 0) != collected) return "machine counters differ from actual transfer accounting"
        if (transfers == 0 && (supplied.any { it != 0 } || collected != 0)) return "machine progress has no actual transfer"
        if (phase in setOf(MachinePhase.RETURN,MachinePhase.DONE) && !goal(d)) return "machine left work without satisfying the fixed contract"
        return null
    }
}
