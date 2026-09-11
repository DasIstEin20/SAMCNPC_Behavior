package io.samcnpc.behavior.lumberjack

import io.samcnpc.behavior.kernel.inventory.ItemPickupApproach
import io.samcnpc.core.api.NpcNavigationRequest
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcEntityObservation
import io.samcnpc.core.api.NpcEntityQuery
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcWorldView
import kotlin.math.abs

/** One finite collection window; successful pickups never reset its overall deadline. */
internal object LumberjackCollectionBudget {
    const val MAX_TICKS = 240
    const val QUIET_TICKS = 20

    data class Step(val elapsed: Int, val quiet: Int, val complete: Boolean, val timedOut: Boolean)

    fun advance(elapsed: Int, quiet: Int, hasDrops: Boolean, onGround: Boolean): Step {
        val nextElapsed = (elapsed + 1).coerceAtMost(MAX_TICKS)
        val nextQuiet = if (hasDrops || !onGround) 0 else (quiet + 1).coerceAtMost(QUIET_TICKS)
        val timedOut = nextElapsed >= MAX_TICKS
        return Step(nextElapsed, nextQuiet, nextQuiet >= QUIET_TICKS || timedOut, timedOut)
    }
}

/** Task-local item selection and normal pickup, separate from trunk/elevation sequencing. */
internal object LumberjackDropCollector {
    sealed interface Result {
        data class Running(val action: NpcActionResult) : Result
        data class Complete(val remainingDrops: Int, val timedOut: Boolean) : Result
    }

    fun tick(npc: NpcFacade, world: NpcWorldView, job: LumberjackDemoJob): Result {
        val anchor = job.trunkBasePosition ?: return Result.Complete(0, false)
        val snapshot = npc.snapshot()
        // Anchor observation to the tree, not its moving collector: chasing a nearer drop
        // must not silently remove the far side of the tree from the collection plan.
        val drops = world.queryEntities(NpcEntityQuery(
            center = NpcPosition(anchor.x + 0.5, anchor.y.toDouble(), anchor.z + 0.5),
            radius = 16.0, limit = 64, typeIds = setOf("minecraft:item"),
        )).filter { it.alive && it.isTaskDrop() && inTreeVolume(it.position, anchor) }
        val budget = LumberjackCollectionBudget.advance(job.pickupTicks, job.pickupQuietTicks, drops.isNotEmpty(), snapshot.onGround)
        job.pickupTicks = budget.elapsed
        job.pickupQuietTicks = budget.quiet
        if (budget.complete) {
            npc.stopControl()
            return Result.Complete(drops.size, budget.timedOut)
        }
        // Falling items keep the window open, but never become floating navigation goals.
        val selection = ItemPickupApproach.select(snapshot.position, world, drops)
        val drop = selection?.drop
        if (drop == null) {
            npc.stopControl()
            return Result.Running(NpcActionResult.running("waiting for tree/scaffold drops to land and the pickup area to settle"))
        }
        val standing = selection.standing
        if (standing != null) {
            val navigation = npc.navigateTo(NpcNavigationRequest(standing, NAVIGATION_SPEED_MULTIPLIER, arrivalDistance = 0.65))
            if (navigation.status == NpcActionStatus.ACCEPTED || navigation.status == NpcActionStatus.RUNNING || navigation.status == NpcActionStatus.SUCCEEDED) {
                val look = lookTowards(npc, drop.position)
                if (look.status != NpcActionStatus.SUCCEEDED) return Result.Running(look)
            }
            return Result.Running(NpcActionResult.running("collecting a grounded tree drop: ${navigation.code}: ${navigation.detail}"))
        }
        npc.stopControl()
        val pickup = npc.pickupItem(drop.uuid)
        return Result.Running(NpcActionResult.running("collecting a tree drop: ${pickup.code}: ${pickup.detail}"))
    }

    private fun NpcEntityObservation.isTaskDrop(): Boolean {
        val itemId = itemStack?.itemId ?: return false
        val path = itemId.substringAfter(':', itemId)
        return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae") ||
            path.endsWith("_sapling") || path.endsWith("_leaves") ||
            itemId == "minecraft:stick" || itemId == "minecraft:apple" || itemId == "minecraft:snowball"
    }

    private fun inTreeVolume(position: NpcPosition, anchor: NpcBlockPosition): Boolean =
        abs(position.x - anchor.x - 0.5) <= 7.0 && abs(position.z - anchor.z - 0.5) <= 7.0 && abs(position.y - anchor.y) <= 12.0
}
