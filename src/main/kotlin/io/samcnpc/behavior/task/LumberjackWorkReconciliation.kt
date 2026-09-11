package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcWorldView

/** Observe effects and bound reload work; no item reconstruction or block mutation is permitted. */
internal object LumberjackWorkReconciliation {
    sealed interface Result {
        data object Ready : Result
        data object Pending : Result
        data class Unavailable(val detail: String) : Result
        data class Mismatch(val detail: String) : Result
    }

    fun observePending(state: LumberjackTaskState, world: NpcWorldView): String? {
        val pending = state.pendingBreak ?: return null
        val actual = world.observeBlock(pending.position) ?: return null
        if (actual.isAir) {
            if (pending.position !in state.observedRemovedBlocks && state.observedRemovedBlocks.size >= LumberjackTaskState.MAX_REMOVED_BLOCKS) return "removed-block journal capacity exceeded"
            state.observedRemovedBlocks.add(pending.position)
            state.pendingBreak = null
        } else if (actual.blockId != pending.blockId) {
            return "pending work block changed identity; no replay authorized"
        }
        return null
    }

    fun resume(state: LumberjackTaskState, world: NpcWorldView): Result {
        val pending = state.pendingBreak
        if (pending != null && world.observeBlock(pending.position) == null) return Result.Unavailable("pending work block cannot be observed after resume")
        val blocks = state.reconciliationBlocks ?: state.observedRemovedBlocks.toList().also { state.reconciliationBlocks = it }
        repeat(128) {
            if (state.reconciliationCursor >= blocks.size) {
                state.reconcileWorld = false
                state.reconciliationBlocks = null
                return Result.Ready
            }
            val position = blocks[state.reconciliationCursor]
            val actual = world.observeBlock(position) ?: return Result.Unavailable("recorded removed block cannot be observed after resume")
            val ownSupport = state.job.pillarSession?.placedBlockIds?.get(position)
            if (!actual.isAir && actual.blockId != ownSupport) return Result.Mismatch("a recorded removed block exists again at $position; no replay authorized")
            state.reconciliationCursor++
        }
        return Result.Pending
    }
}
