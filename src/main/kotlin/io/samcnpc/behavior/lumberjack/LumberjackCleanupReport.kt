package io.samcnpc.behavior.lumberjack

import io.samcnpc.behavior.kernel.elevation.TemporaryPillarState
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.behavior.task.UnresolvedWorkBlock
import io.samcnpc.behavior.task.UnresolvedWorkStore
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcWorldView
import net.minecraft.server.MinecraftServer

internal object LumberjackCleanupReport {
    fun preserve(server: MinecraftServer, job: LumberjackDemoJob, world: NpcWorldView?, reason: String): NpcActionResult {
        val session = job.pillarSession ?: return NpcActionResult.succeeded("no scaffold session")
        val positions = session.placedPositions.toMutableList()
        // Placement can be accepted between the parent's ticks. Cancellation must retain that
        // pending obligation too, even when identity has not yet been verified by the kernel.
        val pending = session.currentPlacement
        if (session.state == TemporaryPillarState.VERIFY_PLACEMENT && pending != null && pending !in positions) positions.add(pending)
        val reports = positions.filterNot { world != null && world.dimensionId == job.dimensionId && world.observeBlock(it)?.isAir == true }.map {
            UnresolvedWorkBlock(job.dimensionId, it, session.placedBlockIds[it], reason.take(128))
        }
        return UnresolvedWorkStore.forServer(server).record(job.npcUuid, reports)
    }
}
