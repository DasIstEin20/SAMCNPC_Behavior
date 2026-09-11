package io.samcnpc.behavior.lumberjack

import io.samcnpc.behavior.kernel.navigation.isClearPlayerStandingCell
import io.samcnpc.behavior.kernel.navigation.isLeafOrSupportedSnowObstacle
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.behavior.lumberjack.model.LumberjackChestAccessStage
import io.samcnpc.behavior.lumberjack.tree.isLumberjackWoodLog
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcWorldView

/** Chest travel has its own access continuation, including during a suspended tree's capacity run. */
internal object LumberjackChestTravel {
    const val MAX_ACCESS_BLOCKS = 24
    const val MAX_ACCESS_TICKS = 100
    private const val ARRIVAL_DISTANCE = 3.5
    private const val APPROACH_DISTANCE = 2.5
    // Native pathfinding may choose a node one block short and then apply its waypoint
    // tolerance. Prefer adjacent stances so both margins fit inside the arrival envelope.
    private const val PREFERRED_APPROACH_DISTANCE = 1.5

    fun move(npc: NpcFacade, world: NpcWorldView, job: LumberjackDemoJob): MoveTowardProgress {
        val origin = npc.snapshot().position
        if (isWithinDistance(origin, job.chestPosition, ARRIVAL_DISTANCE)) return MoveTowardProgress.ARRIVED
        val previous = job.chestApproach
        if (previous == null || !world.isClearPlayerStandingCell(previous)) {
            val chest = job.chestPosition
            val candidates = mutableListOf<NpcBlockPosition>()
            for (x in -2..2) for (z in -2..2) for (y in -2..2) {
                val candidate = NpcBlockPosition(chest.x + x, chest.y + y, chest.z + z)
                if (isWithinDistance(blockNavigationPosition(candidate), chest, APPROACH_DISTANCE) && world.isClearPlayerStandingCell(candidate)) {
                    candidates.add(candidate)
                }
            }
            val adjacent = candidates.filter { candidate ->
                isWithinDistance(blockNavigationPosition(candidate), chest, PREFERRED_APPROACH_DISTANCE)
            }
            // Retain wider supported stances for containers on unusual elevations when
            // no adjacent standing cell exists; do not reject an otherwise valid route.
            val approaches = if (adjacent.isEmpty()) candidates else adjacent
            job.chestApproach = approaches.minWithOrNull(
                compareBy<NpcBlockPosition> { distanceSquared(origin, blockNavigationPosition(it)) }
                    .thenBy { it.x }.thenBy { it.y }.thenBy { it.z },
            )
        }
        val approach = job.chestApproach ?: return MoveTowardProgress.FAILED(
            NpcActionResult.failed("no supported standing cell is available within chest interaction reach", NpcActionCode.NOT_FOUND),
        )
        return navigateAndLook(npc, blockNavigationPosition(approach), blockCenter(job.chestPosition))
    }

    fun beginAccess(job: LumberjackDemoJob, obstruction: NpcBlockPosition): NpcActionResult {
        if (job.chestAccessAttempts >= MAX_ACCESS_BLOCKS) {
            return NpcActionResult.failed("chest route exhausted its $MAX_ACCESS_BLOCKS local tree/foliage clearances", NpcActionCode.WORLD_REJECTED)
        }
        job.chestAccessAttempts++
        job.chestAccessTarget = obstruction
        job.chestAccessTicks = 0
        job.chestAccessStage = LumberjackChestAccessStage.CLEAR_FOLIAGE
        job.chestAccessQuietTicks = 0
        return NpcActionResult.running("clearing the route's obstructing tree/foliage at $obstruction before continuing to the chest")
    }

    fun clearAccess(npc: NpcFacade, world: NpcWorldView, job: LumberjackDemoJob): NpcActionResult {
        val target = checkNotNull(job.chestAccessTarget)
        val isWood = world.observeBlock(target)?.isLumberjackWoodLog() == true
        // Core completes mining during its own entity tick. Observe the changed block on
        // the next Behavior tick; continueBlockBreak only reports an action still running.
        if (job.chestAccessStage == LumberjackChestAccessStage.CUT_WOOD && !isWood) {
            job.chestAccessStage = LumberjackChestAccessStage.COLLECT_WOOD
            job.chestAccessTicks = 0
            job.chestAccessQuietTicks = 0
        }
        if (job.chestAccessStage == LumberjackChestAccessStage.COLLECT_WOOD) return LumberjackChestWoodCollector.tick(npc, world, job)
        npc.stopControl()
        if (!isWood && !world.isLeafOrSupportedSnowObstacle(target)) {
            npc.abortBlockBreak()
            job.chestAccessTarget = null
            job.chestAccessTicks = 0
            return NpcActionResult.running("chest route obstruction is gone; resuming the same container approach")
        }
        if (++job.chestAccessTicks > MAX_ACCESS_TICKS) {
            npc.abortBlockBreak()
            return NpcActionResult.failed("chest route could not clear $target within $MAX_ACCESS_TICKS ticks", NpcActionCode.WORLD_REJECTED)
        }
        val active = npc.snapshot().blockBreak
        if (active == null && (!isWithinDistance(npc.snapshot().position, target, 4.5) || !canSeeWorkBlock(npc, target, world))) {
            npc.abortBlockBreak()
            job.chestAccessTarget = null
            job.chestAccessTicks = 0
            return NpcActionResult.running("chest route obstruction at $target is no longer visible and reachable; resuming movement before reselecting clearance")
        }
        if (active != null && active.position != target) {
            return NpcActionResult.rejected("another block action is active while clearing the chest route", NpcActionCode.CONFLICT)
        }
        val look = lookTowards(npc, blockCenter(target))
        if (look.status in FAILURES) return look
        val result = if (active == null) npc.startBlockBreak(target) else npc.continueBlockBreak()
        if (isWood && result.status in setOf(NpcActionStatus.ACCEPTED, NpcActionStatus.RUNNING, NpcActionStatus.SUCCEEDED)) {
            job.chestAccessStage = LumberjackChestAccessStage.CUT_WOOD
        }
        return result
    }

    private val FAILURES = setOf(NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED)
}
