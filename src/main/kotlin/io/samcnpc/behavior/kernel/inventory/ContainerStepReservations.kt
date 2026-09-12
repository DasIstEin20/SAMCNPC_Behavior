package io.samcnpc.behavior.kernel.inventory

import io.samcnpc.behavior.kernel.work.SpatialWorkClaimKernel
import io.samcnpc.core.api.*
import java.util.UUID

/** One admitted physical transfer, then release. A container lease is not a stock guarantee or permission. */
internal object ContainerStepReservations {
    private val claims = SpatialWorkClaimKernel(leaseTicks = 4, separationBlocks = 0.0)
    fun transfer(taskId: UUID, npc: NpcFacade, world: NpcWorldView, position: NpcBlockPosition, itemId: String, count: Int,
                 direction: ContainerTransferDirection, inventorySlot: Int? = null, containerSlot: Int? = null): ContainerTransferStep {
        val snapshot = npc.snapshot()
        val claim = claims.renewOrClaim(npc.npcUuid, snapshot.dimensionId, position, snapshot.gameTime, taskId)
        if (claim != SpatialWorkClaimKernel.Result.Acquired && claim != SpatialWorkClaimKernel.Result.Held) {
            return ContainerTransferStep(NpcActionResult.running("container reservation $claim; waiting within the original task budget"), problem = ContainerTransferProblem.RESERVED)
        }
        try { return ContainerTransferKernel.transfer(npc, world, position, itemId, count, direction, inventorySlot, containerSlot) }
        finally { claims.release(npc.npcUuid, taskId) }
    }
    fun release(npcUuid: UUID) = claims.release(npcUuid)
    fun clear() = claims.clear()
}
