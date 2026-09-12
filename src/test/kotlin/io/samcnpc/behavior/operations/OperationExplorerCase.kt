package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import net.minecraft.world.item.Items

internal object OperationExplorerCase {
    val kinds=setOf(OperationKind.EXPLORER_LEG,OperationKind.EXPLORER_RETURN)
    fun prepare(s: OperationScene) {
        s.give(Items.DIAMOND,7)
        s.assign(ExplorerTaskDefinition(s.npc.snapshot().dimensionId,s.start,radius=16,maxCells=6,budget=TaskBudget(2400)))
    }
    fun checkpoint(s: OperationScene): Boolean {
        val state=checkNotNull(s.record.primary.explorer)
        return if (s.kind == OperationKind.EXPLORER_LEG) state.nodes.size == 2 && state.pending != null && s.npc.snapshot().navigation != null
            else state.phase == ExplorerPhase.RETURN && state.cursor > 0 && s.npc.snapshot().navigation != null
    }
    fun verify(s: OperationScene) {
        s.requireCompleted();s.requireReturned()
        val state=checkNotNull(s.record.primary.explorer)
        check(state.nodes.size == 6 && state.cursor == 0 && state.phase == ExplorerPhase.DONE && state.stop == ExplorerStop.CELL_LIMIT)
        check(state.nodes.map { it.x to it.z }.toSet().size == 6 && state.pending == null)
        check(s.carried("minecraft:diamond") == 7)
    }
}
