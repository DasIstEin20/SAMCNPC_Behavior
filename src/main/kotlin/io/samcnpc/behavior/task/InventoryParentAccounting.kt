package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.ContainerTransferObservation
import io.samcnpc.behavior.kernel.inventory.ContainerTransferDirection
import io.samcnpc.core.api.*

/** Runs in the same selected step as the real transfer, before next-tick passive observation. */
internal object InventoryParentAccounting {
    fun confirm(record: TaskRecord, npc: NpcFacade, observation: ContainerTransferObservation): String? {
        if (record.frames.size == 1) return null
        PlantingAccounting.transfer(record,npc,observation)?.let { return it }
        val mining: ProducedCargoState? = record.primary.mining ?: record.primary.food ?: record.primary.farming
        if (mining != null) {
            val role = if (observation.direction == ContainerTransferDirection.WITHDRAW) ProducedTransfer.SUPPLY else ProducedTransfer.AUXILIARY_UNLOAD
            val problem = mining.resources.transfer(observation, role, HarvestResources.inventoryCounts(npc), if (record.primary.food != null || record.primary.farming != null) ProducedGain.STOCK else ProducedGain.OUTPUT)
            if (problem != null) return problem
            if (observation.direction == ContainerTransferDirection.DEPOSIT) {
                if (observation.position !in mining.checkpoints && mining.checkpoints.size >= 32) return "mining recipient history exceeds bounds"
                mining.checkpoints[observation.position] = ContainerCheckpoint(observation.blockId, observation.containerSize)
                val row = mining.deliveries[observation.position].orEmpty()
                mining.deliveries[observation.position] = row + (observation.itemId to ((row[observation.itemId] ?: 0) + observation.moved))
            }
        }
        val wood = record.primary.lumberjack
        if (wood != null) {
            val after = HarvestResources.inventoryCounts(npc)
            val beforeContainer = mapOf(observation.itemId to observation.containerBefore)
            val afterContainer = mapOf(observation.itemId to observation.containerAfter)
            val problem = wood.resources.observeStep(after, beforeContainer, afterContainer, placement = false)
            if (problem != null) return problem
            if (observation.direction == ContainerTransferDirection.DEPOSIT) {
                val previous = wood.deliveries[observation.position].orEmpty()
                if (observation.position !in wood.deliveries && wood.deliveries.size >= 32) return "inventory recipient history exceeds wood accounting bounds"
                wood.deliveries[observation.position] = previous + (observation.itemId to ((previous[observation.itemId] ?: 0) + observation.moved))
            }
            if (wood.job.chestPosition == observation.position) {
                val counts = wood.containerCounts.toMutableMap()
                if (observation.containerAfter == 0) counts.remove(observation.itemId) else counts[observation.itemId] = observation.containerAfter
                wood.containerCounts = counts; wood.containerSize = observation.containerSize
            }
        }
        // Auxiliary supplies are outside the cargo contract: gains stay protected stock,
        // while the child report records their actual authorized container provenance.
        return TaskTransport.observeInventory(record, npc)
    }
}
