package io.samcnpc.behavior.task

import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.core.api.*

internal data class LumberjackSupplyCheckpoint(val blockId: String, val size: Int, var counts: Map<String, Int>)
internal class LumberjackSupplyState(
    var selected: NpcBlockPosition? = null,
    val checkpoints: MutableMap<NpcBlockPosition, LumberjackSupplyCheckpoint> = linkedMapOf(),
)

/** A finite wood parent selects only explicitly permitted sources; the executor still equips physically. */
internal object LumberjackSupply {
    fun preparing(state: LumberjackTaskState): Boolean = state.job.phase == LumberjackDemoPhase.TRAVEL_TO_CHEST || state.job.phase == LumberjackDemoPhase.PREPARE_EQUIPMENT
    fun position(definition: LumberjackTaskDefinition, state: LumberjackTaskState): NpcBlockPosition =
        if (definition.version >= 2 && preparing(state)) state.supplies?.selected ?: definition.destination else definition.destination

    fun prepare(definition: LumberjackTaskDefinition, state: LumberjackTaskState, npc: NpcFacade, world: NpcWorldView): String? {
        state.job.externalSuppliesAllowed = definition.version == 1 || definition.supplySources != null
        if (definition.version == 1 || !preparing(state) || definition.supplySources == null) { bind(definition, state); return null }
        val supplies = checkNotNull(state.supplies)
        val selected = supplies.selected
        val choices = definition.supplySources
        val snapshot = npc.snapshot()
        val needsAxe = !snapshot.ignoreMissingMiningTool && !snapshot.bareHandsMiningOnly && npc.inventoryContents().none { it.knowledge.toolKind == NpcToolKind.AXE && !it.stack.isEmpty }
        val existing = selected?.let { usable(supplies, it, world) }
        if (existing != null && (!needsAxe || existing.slots.any { it.knowledge.toolKind == NpcToolKind.AXE && !it.stack.isEmpty })) { bind(definition, state); return null }
        val ordered = if (choices.preference == ContainerPreference.ORDERED) choices.positions else choices.positions.sortedWith(
            compareBy<NpcBlockPosition> { TaskNavigator.distanceSquared(snapshot.position, TransportTaskDefinition.center(it)) }.thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
        var fallback: NpcBlockPosition? = null
        var chosen: NpcBlockPosition? = null
        for (candidate in ordered) {
            val container = usable(supplies, candidate, world) ?: continue
            if (fallback == null) fallback = candidate
            if (!needsAxe || container.slots.any { it.knowledge.toolKind == NpcToolKind.AXE && !it.stack.isEmpty }) { chosen = candidate; break }
        }
        val next = chosen ?: fallback ?: return "no authorized loaded wood supply source with the expected shape/type"
        supplies.selected = next
        if (selected != next) { state.job.phase = LumberjackDemoPhase.TRAVEL_TO_CHEST; state.job.chestApproach = null }
        bind(definition, state)
        return null
    }

    private fun usable(state: LumberjackSupplyState, position: NpcBlockPosition, world: NpcWorldView): NpcBlockContainerObservation? {
        val container = world.observeBlockContainer(position) ?: return null
        val counts = TaskLumberjack.containerCounts(container) ?: return null
        val block = world.observeBlock(position) ?: return null
        val previous = state.checkpoints[position]
        if (previous != null && (previous.size != container.containerSize || previous.blockId != block.blockId)) return null
        if (previous == null) {
            if (state.checkpoints.size >= 8) return null
            state.checkpoints[position] = LumberjackSupplyCheckpoint(block.blockId, container.containerSize, counts)
        }
        return container
    }
    fun bind(definition: LumberjackTaskDefinition, state: LumberjackTaskState) {
        val current = position(definition, state)
        if (state.job.chestPosition != current) { state.job.chestPosition = current; state.job.chestApproach = null }
    }
}
