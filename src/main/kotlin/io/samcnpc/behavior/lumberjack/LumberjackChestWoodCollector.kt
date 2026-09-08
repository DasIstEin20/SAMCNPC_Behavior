package io.samcnpc.behavior.lumberjack

import com.mojang.logging.LogUtils
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.behavior.lumberjack.model.LumberjackChestAccessStage
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcEntityObservation
import io.samcnpc.core.api.NpcEntityQuery
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcWorldView

/** Route wood uses a separate pickup continuation so a capacity run preserves its tree plan. */
internal object LumberjackChestWoodCollector {
    private val logger = LogUtils.getLogger()

    fun tick(npc: NpcFacade, world: NpcWorldView, job: LumberjackDemoJob): NpcActionResult {
        val anchor = checkNotNull(job.chestAccessTarget)
        val snapshot = npc.snapshot()
        val drops = world.queryEntities(NpcEntityQuery(
            center = blockCenter(anchor), radius = 4.0, limit = 16, typeIds = setOf("minecraft:item"),
        )).filter { it.alive && it.isWoodDrop() }
        val budget = LumberjackCollectionBudget.advance(
            job.chestAccessTicks, job.chestAccessQuietTicks, drops.isNotEmpty(), snapshot.onGround,
        )
        job.chestAccessTicks = budget.elapsed
        job.chestAccessQuietTicks = budget.quiet
        if (budget.complete) {
            npc.stopControl()
            if (budget.timedOut && drops.isNotEmpty()) {
                logger.warn("Chest route wood pickup timed out npc={} anchor={} remainingDrops={}", job.npcUuid, anchor, drops.size)
            }
            job.chestAccessTarget = null
            job.chestAccessTicks = 0
            job.chestAccessQuietTicks = 0
            job.chestAccessStage = LumberjackChestAccessStage.CLEAR_FOLIAGE
            return NpcActionResult.running("route wood collection finished; resuming the same chest approach")
        }
        val drop = drops.filter { it.position.y <= snapshot.position.y + 0.9 }
            .minWithOrNull(compareBy<NpcEntityObservation> { distanceSquared(snapshot.position, it.position) }.thenBy { it.uuid.toString() })
        if (drop == null) {
            npc.stopControl()
            return NpcActionResult.running("waiting for route wood drops to land and the pickup area to settle")
        }
        if (distanceSquared(snapshot.position, drop.position) > 4.0) {
            val navigation = npc.navigateTo(drop.position, NAVIGATION_SPEED_MULTIPLIER)
            return NpcActionResult.running("approaching route wood: ${navigation.code}: ${navigation.detail}")
        }
        npc.stopControl()
        val pickup = npc.pickupItem(drop.uuid)
        return NpcActionResult.running("collecting route wood: ${pickup.code}: ${pickup.detail}")
    }

    private fun NpcEntityObservation.isWoodDrop(): Boolean {
        val itemId = itemStack?.itemId ?: return false
        val path = itemId.substringAfter(':', itemId)
        return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae")
    }
}
