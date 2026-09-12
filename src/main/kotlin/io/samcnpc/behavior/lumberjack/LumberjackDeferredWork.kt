package io.samcnpc.behavior.lumberjack

import io.samcnpc.behavior.lumberjack.model.LumberjackDeferredTarget
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.behavior.lumberjack.tree.isLumberjackWoodLog
import io.samcnpc.core.api.*

/** Revisit a blocked branch only after its observed obstruction disappears, once per target. */
internal object LumberjackDeferredWork {
    const val MAX_TARGETS = 32
    fun record(job: LumberjackDemoJob, world: NpcWorldView, obstruction: NpcBlockPosition?) {
        if (!job.deferredWoodEnabled || obstruction == null || job.deferredWood.size >= MAX_TARGETS) return
        val target = job.targetPosition ?: return
        if (target == obstruction || job.deferredWood.any { it.target == target }) return
        val block = world.observeBlock(target) ?: return
        val blocker = world.observeBlock(obstruction) ?: return
        if (!block.isLumberjackWoodLog() || blocker.isAir) return
        val selection = job.workSelection ?: return
        if (!selection.area.contains(target) || !selection.wood.matches(block.blockId)) return
        job.deferredWood.add(LumberjackDeferredTarget(target, block.blockId, obstruction, blocker.blockId,
            (job.failedWorkAttempts + 1).coerceAtMost(3)))
    }
    fun candidate(job: LumberjackDemoJob, world: NpcWorldView): LumberjackDeferredTarget? {
        if (!job.deferredWoodEnabled) return null
        val selection = job.workSelection ?: return null
        return job.deferredWood.firstOrNull { entry ->
            !entry.revisited && selection.area.contains(entry.target) && selection.wood.matches(entry.targetBlockId) &&
                world.observeBlock(entry.target)?.blockId == entry.targetBlockId && world.observeBlock(entry.obstruction)?.isAir == true
        }
    }
}
