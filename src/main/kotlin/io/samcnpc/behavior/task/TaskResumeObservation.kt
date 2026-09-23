package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcFacade

/** Observe a coherent live body at the explicit resume boundary, before its next passive pickup. */
internal object TaskResumeObservation {
    fun prime(record: TaskRecord, npc: NpcFacade) {
        record.primary.fieldPreparation?.let { it.reconcileWorld=true;it.reconcileCursor=0 }
        // A genuinely loaded body has a frozen pre-pickup snapshot; retain that stronger check.
        // A never-deserialized body has none, so an exact present match is the valid boundary.
        if (npc.inventoryLoadSnapshot() != null) return
        for (frame in record.frames) {
            val resources = frame.inventory?.resources ?: continue
            val actual = HarvestResources.inventoryCounts(npc)
            if (resources.mustReconcileLoad && !resources.uncertain && actual == resources.retained()) check(resources.reconcileLoad(null, actual) == null)
        }
        val mining = record.primary.fieldPreparation?.resources ?: record.primary.machine?.resources ?: record.primary.mining?.resources?.physical ?: record.primary.food?.resources?.physical ?: record.primary.farming?.resources?.physical
        if (mining != null && mining.mustReconcileLoad && !mining.uncertain) {
            val actual = HarvestResources.inventoryCounts(npc)
            if (actual == mining.retained()) check(mining.reconcileLoad(null, actual) == null)
        }
        record.primary.planting?.resources?.let { planting ->
            val actual=HarvestResources.inventoryCounts(npc)
            if(planting.mustReconcileLoad && !planting.uncertain && actual == planting.retained()) check(planting.reconcileLoad(null,actual) == null)
        }
        val wood = record.primary.lumberjack?.resources
        if (wood != null && wood.mustReconcileLoad && !wood.uncertain) {
            val actual = HarvestResources.inventoryCounts(npc)
            if (actual == wood.retained()) check(wood.reconcileLoad(null, actual) == null)
        }
        val cargo = record.primary.transport?.ledger
        if (cargo != null && cargo.mustReconcileLoad && !cargo.uncertain) {
            val actual = TaskDelivery.inventoryCount(npc, cargo.itemId)
            if (actual == cargo.retained) check(cargo.reconcileLoad(null, actual) == null)
        }
    }
}
