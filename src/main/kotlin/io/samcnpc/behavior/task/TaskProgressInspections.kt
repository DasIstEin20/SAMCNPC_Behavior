package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.OperationCountUnit
import io.samcnpc.behavior.api.OperationCountUnit.*
import io.samcnpc.behavior.api.OperationMeasuredCount
import io.samcnpc.behavior.api.OperationProgress

internal object TaskProgressInspections {
    fun capture(frame: TaskFrame, terminalCombat: TaskCombatOutcome? = null): OperationProgress = when (val d = frame.definition) {
        is NavigateTaskDefinition -> OperationProgress.Observed(null, emptyList())
        is DeliveryTaskDefinition -> if (d.version >= 2) transport(frame) else delivery(frame)
        is TransportTaskDefinition -> transport(frame)
        is LumberjackTaskDefinition -> {
            val s = frame.lumberjack
            if (s == null) OperationProgress.NotInitialized else OperationProgress.Observed(s.job.phase.name, listOf(
                count("netDeliveredWood", s.resources.delivered(d.wood), ITEMS),
                count("removedWoodBlocks", s.removedWood.size, BLOCKS),
                count("failedWorkAttempts", s.job.failedWorkAttempts, ATTEMPTS)), s.resources.uncertain,
                s.resources.mustReconcileLoad || s.reconcileWorld)
        }
        is MiningTaskDefinition -> {
            val s = frame.mining
            if (s == null) OperationProgress.NotInitialized else OperationProgress.Observed(s.phase.name, listOf(
                count("deliveredItems", s.delivered(d), ITEMS), count("carriedOutputItems", s.cargo(d), ITEMS),
                count("removedResourceBlocks", s.removedResources(d), BLOCKS),
                count("clearedCells", s.selection.cleared.size, CELLS),
                count("confirmedObjective", s.confirmed(d), when (d.counting) {
                    MiningCounting.DELIVERED_ITEMS -> ITEMS
                    MiningCounting.REMOVED_RESOURCE_BLOCKS -> BLOCKS
                    MiningCounting.CLEARED_VOLUME -> VOLUMES
                })), s.resources.physical.uncertain, s.resources.physical.mustReconcileLoad || s.reconcileWorld, s.stop?.name)
        }
        is FarmTaskDefinition -> {
            val s = frame.farming
            if (s == null) OperationProgress.NotInitialized else OperationProgress.Observed(s.phase.name, listOf(
                count("deliveredItems", s.delivered(d), ITEMS), count("carriedOutputItems", s.cargo(d), ITEMS),
                count("cycleIndex", s.cycle, INDEX), count("harvestedBlocks", s.harvested.values.sumOf { it.toLong() }, BLOCKS),
                count("plantedBlocks", s.planted.values.sumOf { it.toLong() }, BLOCKS),
                count("growthRemaining", s.growthRemaining, TICKS)), s.resources.physical.uncertain,
                s.resources.physical.mustReconcileLoad || s.reconcileWorld, s.stop?.name)
        }
        is PlantingTaskDefinition -> {
            val s = frame.planting
            if (s == null) OperationProgress.NotInitialized else OperationProgress.Observed(s.phase.name, listOf(
                count("completedLayouts", s.completed(d), LAYOUTS), count("placedSaplings", s.planted(), ITEMS),
                count("skippedSites", s.skipped.size, CELLS)), s.resources.uncertain,
                s.resources.mustReconcileLoad || s.reconcileWorld, s.stop?.name)
        }
        is FoodTaskDefinition -> {
            val s = frame.food
            if (s == null) OperationProgress.NotInitialized else OperationProgress.Observed(s.phase.name, listOf(
                count("deliveredItems", s.delivered(d), ITEMS), count("carriedOutputItems", s.cargo(d), ITEMS),
                count("retainedFoodItems", s.retainedFood(), ITEMS), count("harvestedBushes", s.harvested.size, BLOCKS),
                count("huntTargetsAttempted", s.hunts.size, ATTEMPTS)), s.resources.physical.uncertain,
                s.resources.physical.mustReconcileLoad, s.stop?.name)
        }
        is FishingTaskDefinition -> {
            val s = frame.fishing
            if (s == null) OperationProgress.NotInitialized else OperationProgress.Observed(s.phase.name, listOf(
                count("casts", s.casts, CASTS), count("caught", s.caught, CATCHES),
                count("collectedCatches", s.collectedCatches, CATCHES)), s.resources.uncertain, s.resources.mustReconcileLoad)
        }
        is ExplorerTaskDefinition -> {
            val s = frame.explorer
            if (s == null) OperationProgress.NotInitialized else OperationProgress.Observed(s.phase.name, listOf(
                count("visitedCells", s.nodes.size, CELLS), count("rejectedLegs", s.rejectedLegs, ATTEMPTS)), stopReason = s.stop?.name)
        }
        is MachineTaskDefinition -> {
            val s = frame.machine
            if (s == null) OperationProgress.NotInitialized else {
                val counters = s.supplied.mapIndexed { index, value -> count("suppliedFeed" + index, value, ITEMS) } +
                    listOf(count("collectedOutput", s.collected, ITEMS), count("transfers", s.transfers, STEPS),
                        count("idleTicks", s.idleTicks, TICKS), count("pollRemaining", s.pollRemaining, TICKS))
                OperationProgress.Observed(s.phase.name, counters, s.resources.uncertain, s.resources.mustReconcileLoad)
            }
        }
        is InventoryTaskDefinition -> {
            val s = frame.inventory
            if (s == null) OperationProgress.NotInitialized else OperationProgress.Observed(s.phase.name, listOf(
                count("steps", s.steps, STEPS), count("workRemaining", s.workRemaining, TICKS),
                count("suppliedItems", s.resources.entries.values.sumOf { it.supplied.toLong() }, ITEMS),
                count("deliveredItems", s.resources.entries.values.sumOf { it.delivered.toLong() }, ITEMS),
                count("pickedItems", s.picked.values.sumOf { it.toLong() }, ITEMS)),
                s.resources.uncertain, s.resources.mustReconcileLoad, s.reason?.name ?: s.lastProblem?.name)
        }
        is AttackTaskDefinition, is CombatMissionDefinition -> combat(frame, terminalCombat)
    }

    private fun delivery(frame: TaskFrame): OperationProgress {
        val s = frame.resources ?: return OperationProgress.NotInitialized
        return OperationProgress.Observed(null, listOf(count("deliveredItems", s.delivered, ITEMS),
            count("confirmedRetainedItems", s.retained, ITEMS)), s.uncertain, s.observedLoadGeneration == null)
    }

    private fun transport(frame: TaskFrame): OperationProgress {
        val s = frame.transport ?: return OperationProgress.NotInitialized
        val ledger = s.ledger
        return OperationProgress.Observed(s.phase.name, listOf(count("deliveredItems", ledger.delivered, ITEMS),
            count("withdrawnItems", ledger.withdrawn, ITEMS), count("carriedCargo", ledger.cargo, ITEMS),
            count("protectedStock", ledger.protected, ITEMS), count("lostCargo", ledger.cargoLost, ITEMS)),
            ledger.uncertain, ledger.mustReconcileLoad, s.lastProblem?.name)
    }

    private fun combat(frame: TaskFrame, terminalCombat: TaskCombatOutcome?): OperationProgress {
        val s = frame.combat ?: return OperationProgress.NotInitialized
        val phase = when { s.retreating -> "RETREAT"; s.returning -> "RETURN"; else -> null }
        val counts = mutableListOf(count("healingUses", s.healingUses, USES),
            count("recoveryRemaining", s.recoveryTicks, TICKS))
        val definition = frame.definition
        if (definition is AreaAttackTaskDefinition) counts.add(count("confirmedDefeats", s.defeatedTargets.size, DEFEATS))
        if (definition is AttackTaskDefinition && terminalCombat?.targetUuid == definition.targetUuid) {
            counts.add(count("confirmedDefeats", terminalCombat.confirmedKills, DEFEATS))
        }
        when (frame.definition) {
            is DefendTaskDefinition -> counts.add(count("dutyRemaining", s.dutyTicks, TICKS))
            is PatrolTaskDefinition -> counts.addAll(listOf(count("completedRounds", s.patrolRounds, ROUNDS),
                count("waypointIndex", s.patrolIndex, INDEX), count("dwellRemaining", s.dwellTicks, TICKS)))
            else -> Unit
        }
        return OperationProgress.Observed(phase, counts)
    }

    private fun count(name: String, value: Int, unit: OperationCountUnit) = count(name, value.toLong(), unit)
    private fun count(name: String, value: Long, unit: OperationCountUnit) = OperationMeasuredCount(name, value, unit)
}
