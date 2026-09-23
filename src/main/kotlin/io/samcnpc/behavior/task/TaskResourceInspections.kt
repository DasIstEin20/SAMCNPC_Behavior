package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*

/** Copies existing accounting only; no inventory reconciliation, container read or reservation mutation. */
internal object TaskResourceInspections {
    fun capture(frame: TaskFrame): OperationResourceInspection = when (val d = frame.definition) {
        is NavigateTaskDefinition, is ExplorerTaskDefinition, is AttackTaskDefinition, is CombatMissionDefinition ->
            OperationResourceInspection.NotTracked
        is DeliveryTaskDefinition -> if (d.version >= 2) transport(frame) else legacy(frame, d)
        is TransportTaskDefinition -> transport(frame)
        is LumberjackTaskDefinition -> physical(frame.lumberjack?.resources)
        is PrepareFieldTaskDefinition -> physical(frame.fieldPreparation?.resources)
        is PlantingTaskDefinition -> physical(frame.planting?.resources)
        is FishingTaskDefinition -> physical(frame.fishing?.resources)
        is MachineTaskDefinition -> physical(frame.machine?.resources)
        is InventoryTaskDefinition -> physical(frame.inventory?.resources)
        is MiningTaskDefinition -> produced(frame.mining?.resources)
        is FarmTaskDefinition -> produced(frame.farming?.resources)
        is FoodTaskDefinition -> produced(frame.food?.resources)
    }

    private fun physical(resources: HarvestResources?): OperationResourceInspection {
        if (resources == null) return OperationResourceInspection.NotInitialized
        val items = resources.entries.toSortedMap().map { (item, row) -> OperationItemAccounting(item, counts(row)) }
        return OperationResourceInspection.Checkpoint(OperationResourceLedgerKind.PHYSICAL, items,
            resources.uncertain, resources.mustReconcileLoad)
    }

    private fun produced(resources: ProducedResources?): OperationResourceInspection {
        if (resources == null) return OperationResourceInspection.NotInitialized
        val origins = resources.entries
        val items = resources.physical.entries.toSortedMap().map { (item, row) ->
            val counters = counts(row).toMutableList()
            val origin = origins[item]
            if (origin != null) {
                counters.addAll(listOf(count("stock", origin.stock), count("output", origin.output),
                    count("sourceOutput", origin.sourceOutput), count("deliveredOutput", origin.deliveredOutput),
                    count("protectedGathered", origin.protectedGathered)))
            }
            OperationItemAccounting(item, counters)
        }
        return OperationResourceInspection.Checkpoint(OperationResourceLedgerKind.PRODUCED, items,
            resources.physical.uncertain, resources.physical.mustReconcileLoad)
    }

    private fun transport(frame: TaskFrame): OperationResourceInspection {
        val ledger = frame.transport?.ledger ?: return OperationResourceInspection.NotInitialized
        val item = OperationItemAccounting(ledger.itemId, listOf(count("initial", ledger.initial),
            count("retained", ledger.retained), count("withdrawn", ledger.withdrawn), count("delivered", ledger.delivered),
            count("incidentalGained", ledger.incidentalGained), count("incidentalLost", ledger.incidentalLost),
            count("cargoLost", ledger.cargoLost), count("initialCargo", ledger.initialCargo),
            count("cargo", ledger.cargo), count("protectedStock", ledger.protected)))
        return OperationResourceInspection.Checkpoint(OperationResourceLedgerKind.TRANSPORT, listOf(item),
            ledger.uncertain, ledger.mustReconcileLoad)
    }

    private fun legacy(frame: TaskFrame, definition: DeliveryTaskDefinition): OperationResourceInspection {
        val ledger = frame.resources ?: return OperationResourceInspection.NotInitialized
        val item = OperationItemAccounting(definition.itemId, listOf(count("initial", ledger.initial),
            count("confirmedRetained", ledger.retained), count("delivered", ledger.delivered)))
        return OperationResourceInspection.Checkpoint(OperationResourceLedgerKind.LEGACY_DELIVERY, listOf(item),
            ledger.uncertain, ledger.observedLoadGeneration == null)
    }

    private fun counts(row: HarvestResource): List<OperationMeasuredCount> = listOf(count("initial", row.initial),
        count("gathered", row.gathered), count("supplied", row.supplied), count("consumed", row.consumed),
        count("delivered", row.delivered), count("lost", row.lost), count("retained", row.retained))

    private fun count(name: String, value: Int) = OperationMeasuredCount(name, value.toLong(), OperationCountUnit.ITEMS)
}
