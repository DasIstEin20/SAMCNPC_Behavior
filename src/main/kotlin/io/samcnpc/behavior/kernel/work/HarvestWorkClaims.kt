package io.samcnpc.behavior.kernel.work

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.NpcItemPickupCheckEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/** One shared physical harvest partition, including native contact pickup between action ticks. */
internal object HarvestWorkClaims {
    val kernel=SpatialWorkClaimKernel()
    /** Enforces already acquired work leases for contact pickup as well as deliberate collection. */
    @SubscribeEvent
    fun checkPickupReservations(event: NpcItemPickupCheckEvent) {
        val server = BehaviorRuntimeService.serverOrNull() ?: return
        val taskId = io.samcnpc.behavior.task.TaskStore.forServer(server).get(event.npcUuid)?.takeUnless { it.status.terminal }?.id ?: event.npcUuid
        // Derive the envelope from supplied facts: Core may allow a larger configured reach.
        val radius = kotlin.math.sqrt(event.candidates.maxOf { candidate ->
            val dx = candidate.position.x - event.npcPosition.x
            val dy = candidate.position.y - event.npcPosition.y
            val dz = candidate.position.z - event.npcPosition.z
            dx * dx + dy * dy + dz * dz
        })
        val permitted = kernel.incidentalCollectionFilter(event.npcUuid, taskId, event.dimensionId,
            event.npcPosition, radius, event.gameTime)
        for (candidate in event.candidates) {
            if (!permitted(candidate.position)) event.deny(candidate.itemEntityUuid, "drop is inside another NPC's active harvest reservation")
        }
    }

}
