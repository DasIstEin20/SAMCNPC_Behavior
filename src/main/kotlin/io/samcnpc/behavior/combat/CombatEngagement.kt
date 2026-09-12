package io.samcnpc.behavior.combat

import io.samcnpc.behavior.task.CombatTaskState
import io.samcnpc.behavior.task.TaskExecution
import io.samcnpc.behavior.task.TaskNavigator
import io.samcnpc.behavior.task.TaskReason
import io.samcnpc.core.api.*
import kotlin.math.floor
import kotlin.math.sqrt

internal data class CombatStep(val action: NpcActionResult, val end: TaskReason? = null, val retry: Boolean = false)

/** Shared bounded fight/recovery policy. Callers own goal selection, deadlines and result ledgers. */
internal object CombatEngagement {
    fun tick(npc: NpcFacade, world: NpcWorldView, target: NpcEntityObservation, area: CombatTargetSelector.Area,
             tactics: CombatTactics, state: CombatTaskState, execution: TaskExecution): CombatStep {
        val snapshot = npc.snapshot()
        val distanceSquared = TaskNavigator.distanceSquared(snapshot.position, target.position)
        val distance = sqrt(distanceSquared)
        val look = npc.lookAtEntity(target.uuid)
        if (look.status in FAILURE) return CombatStep(look, TaskReason.TARGET_UNAVAILABLE)
        val baseline = state.healingBaseline
        if (baseline != null && snapshot.healthFraction > baseline + 0.001) state.observedHealing = true
        if (snapshot.healthFraction <= tactics.retreatAt && tactics.retreatAt > 0) state.retreating = true
        if (state.retreating && (tactics.retreatAt == 0.0 || snapshot.healthFraction >= tactics.returnAt)) {
            state.retreating = false
            clearRoute(execution)
        }
        if (state.retreating && state.recoveryTicks == 0) {
            return CombatStep(NpcActionResult.succeeded("bounded recovery exhausted; actual healing=${state.observedHealing}"), TaskReason.RECOVERY_EXHAUSTED)
        }
        val rangedAction = snapshot.rangedAttack
        if (rangedAction != null) {
            if (execution.combatActionId == null || rangedAction.actionId != execution.combatActionId) return running("another explicit ranged action owns the hand")
            if (state.retreating || !area.contains(target.position) || target.combat?.visible != true) {
                npc.cancelRangedAttack()
                execution.combatActionId = null
            } else return running("maintaining the selected physical ranged charge")
        }
        val use = snapshot.itemUse
        if (use != null) {
            if (execution.tacticalUseId == null || use.actionId != execution.tacticalUseId) return running("another explicit use owns the hand")
            if (snapshot.equipment.mainHand.combat.healingConsumable && use.hand == NpcHand.MAIN) {
                return CombatStep(npc.continueItemUse())
            }
            if (!state.retreating && snapshot.attackStrength >= 0.9F) {
                npc.cancelItemUse()
                execution.tacticalUseId = null
                return running("lowering shield for a ready attack")
            }
        } else execution.tacticalUseId = null
        if (state.retreating) return recover(npc, world, snapshot, target, area, tactics, state, execution)

        val inventory = npc.inventoryContents()
        val lastChoice = execution.lastEquipmentTick
        if (lastChoice == null || snapshot.gameTime - lastChoice >= 10) {
            execution.lastEquipmentTick = snapshot.gameTime
            val choice = CombatEquipment.choose(snapshot, inventory, tactics, distance)
            if (choice != null) return CombatStep(npc.equipFromInventory(choice.slot, choice.destination))
        }
        val wantsRanged = CombatEquipment.preferRanged(tactics, distance, snapshot.equipment.mainHand.combat.rangedSupported)
        val rangedUsable = tactics.allowed != CombatWeaponAllowance.MELEE &&
            CombatEquipment.canShoot(snapshot.equipment.mainHand, inventory, snapshot.equipment)
        if (rangedUsable && (wantsRanged || NpcItemRole.MELEE_WEAPON !in snapshot.equipment.mainHand.roles)) {
            if (distance < tactics.rangedMinDistance) return stepAway(npc, world, snapshot, target, area, execution)
            if (target.combat?.visible == true && distance <= tactics.rangedMaxDistance) {
                stopMoving(npc, execution)
                val shot = npc.startRangedAttack(target.uuid, NpcHand.MAIN)
                if (shot.status == NpcActionStatus.ACCEPTED || shot.status == NpcActionStatus.RUNNING) {
                    execution.combatActionId = shot.actionId
                    state.attackSubmitted = true
                }
                return CombatStep(shot, retry = shot.status in FAILURE)
            }
            return approach(npc, snapshot, target.position, execution, (tactics.rangedMaxDistance - 1).coerceAtLeast(1.5))
        }
        if (tactics.allowed == CombatWeaponAllowance.RANGED) {
            return CombatStep(NpcActionResult.rejected("ranged-only task has no usable carried weapon/ammunition", NpcActionCode.MISSING_RESOURCE), retry = true)
        }
        if (target.combat?.visible == true && distanceSquared <= 2.4 * 2.4) {
            stopMoving(npc, execution)
            if (snapshot.attackStrength < 0.9F) {
                if (tactics.useShield && NpcItemRole.SHIELD in snapshot.equipment.offHand.roles) return shield(npc, snapshot, execution)
                return running("waiting for the actual melee cooldown")
            }
            val hit = npc.attackEntity(target.uuid)
            if (hit.status == NpcActionStatus.SUCCEEDED) state.attackSubmitted = true
            return CombatStep(hit, if (hit.code == NpcActionCode.PERMISSION_DENIED) TaskReason.PERMISSION_CHANGED else null)
        }
        return approach(npc, snapshot, target.position, execution, 1.5)
    }

    private fun recover(npc: NpcFacade, world: NpcWorldView, snapshot: NpcSnapshot, target: NpcEntityObservation,
                        area: CombatTargetSelector.Area, tactics: CombatTactics, state: CombatTaskState, execution: TaskExecution): CombatStep {
        val inventory = npc.inventoryContents()
        val separation = TaskNavigator.distanceSquared(snapshot.position, target.position)
        if (tactics.heal && state.healingCooldown == 0 && state.healingUses < 16 && separation >= 4.0 * 4.0) {
            val medicine = CombatEquipment.healingItem(inventory)
            if (medicine != null) {
                if (snapshot.itemUse != null) { npc.cancelItemUse(); execution.tacticalUseId = null; return running("lowering shield before healing") }
                if (medicine.slot != snapshot.selectedHotbarSlot) return CombatStep(npc.equipFromInventory(medicine.slot, NpcEquipmentDestination.MAIN_HAND))
                stopMoving(npc, execution)
                val result = npc.startItemUse(NpcHand.MAIN)
                if (result.status == NpcActionStatus.ACCEPTED || result.status == NpcActionStatus.RUNNING) {
                    execution.tacticalUseId = result.actionId
                    state.healingUses++
                    state.healingCooldown = 100
                    state.healingBaseline = snapshot.healthFraction
                } else state.healingCooldown = 40
                return CombatStep(result)
            }
        }
        val move = stepAway(npc, world, snapshot, target, area, execution)
        if (tactics.useShield && NpcItemRole.SHIELD in snapshot.equipment.offHand.roles) shield(npc, npc.snapshot(), execution)
        return move
    }

    private fun shield(npc: NpcFacade, snapshot: NpcSnapshot, execution: TaskExecution): CombatStep {
        if (snapshot.itemUse != null) return CombatStep(npc.continueItemUse())
        val result = npc.startItemUse(NpcHand.OFF)
        if (result.status == NpcActionStatus.ACCEPTED || result.status == NpcActionStatus.RUNNING) execution.tacticalUseId = result.actionId
        return CombatStep(result)
    }

    private fun stepAway(npc: NpcFacade, world: NpcWorldView, snapshot: NpcSnapshot, target: NpcEntityObservation,
                         area: CombatTargetSelector.Area, execution: TaskExecution): CombatStep {
        if (execution.approach == null || execution.lastRetreatPlanTick == null || snapshot.gameTime - checkNotNull(execution.lastRetreatPlanTick) >= 20) {
            if (!io.samcnpc.behavior.runtime.BehaviorPlanning.admit(world, 96, io.samcnpc.behavior.kernel.work.PlanningKind.RETREAT)) {
                val current = execution.approach
                return if (current != null && area.contains(current)) approach(npc, snapshot, current, execution, 0.7)
                    else running("retreat planning deferred by shared budget; active use and original recovery deadline retained")
            }
            execution.approach = retreatPosition(snapshot, target, world, area)
            execution.lastRetreatPlanTick = snapshot.gameTime
            clearRoute(execution)
        }
        val destination = execution.approach
        if (destination == null) {
            stopMoving(npc, execution)
            return running("no safe retreat cell inside the fixed boundary; bounded recovery continues")
        }
        if (TaskNavigator.distanceSquared(snapshot.position, destination) <= 0.8 * 0.8) {
            stopMoving(npc, execution)
            return running("holding a supported recovery position")
        }
        return approach(npc, snapshot, destination, execution, 0.7)
    }

    private fun retreatPosition(snapshot: NpcSnapshot, target: NpcEntityObservation, world: NpcWorldView,
                                area: CombatTargetSelector.Area): NpcPosition? {
        val candidates = mutableListOf<Pair<NpcPosition, Double>>()
        val currentDistance = TaskNavigator.distanceSquared(snapshot.position, target.position)
        for ((dx, dz) in DIRECTIONS) for (step in listOf(3, 6)) for (dy in -1..1) {
            val cell = NpcPosition(floor(snapshot.position.x) + dx * step + 0.5, floor(snapshot.position.y) + dy, floor(snapshot.position.z) + dz * step + 0.5)
            if (!area.contains(cell)) continue
            val separation = TaskNavigator.distanceSquared(cell, target.position)
            if (separation <= currentDistance + 1.0) continue
            val standing = world.observeStandingSpace(cell) ?: continue
            if (!standing.clear || !standing.supported || standing.inFluid) continue
            val cover = world.visibleFrom(cell, target.uuid) == false
            candidates.add(cell to (separation + if (cover) 256.0 else 0.0))
        }
        return candidates.sortedWith(compareByDescending<Pair<NpcPosition, Double>> { it.second }
            .thenBy { TaskNavigator.distanceSquared(snapshot.position, it.first) }.thenBy { it.first.x }.thenBy { it.first.z }).firstOrNull()?.first
    }

    private fun approach(npc: NpcFacade, snapshot: NpcSnapshot, destination: NpcPosition, execution: TaskExecution, arrival: Double): CombatStep {
        val completion = execution.completion
        execution.completion = null
        if (completion != null) return CombatStep(NpcActionResult.running("combat route ended before its actual objective: ${completion.code}"), retry = true)
        val now = snapshot.gameTime
        val old = execution.combatRoute
        val previousRepath = execution.lastRepathTick
        if (old == null || previousRepath == null || (now - previousRepath >= 20 && TaskNavigator.distanceSquared(old.position, destination) >= 0.25)) {
            execution.combatRoute = NpcNavigationRequest(destination, 1.0F, arrival)
            execution.lastRepathTick = now
            execution.bestDistanceSquared = TaskNavigator.distanceSquared(snapshot.position, destination)
        }
        val route = checkNotNull(execution.combatRoute)
        val distance = TaskNavigator.distanceSquared(snapshot.position, route.position)
        if (execution.lastProgressTick == null || distance < execution.bestDistanceSquared - 0.0625) {
            execution.bestDistanceSquared = distance
            execution.lastProgressTick = now
        }
        val lastProgress = checkNotNull(execution.lastProgressTick)
        if (now < lastProgress || now - lastProgress >= 120) return CombatStep(NpcActionResult.running("combat made no physical progress for 120 ticks"), retry = true)
        val result = npc.navigateTo(route)
        if (result.status == NpcActionStatus.ACCEPTED || result.status == NpcActionStatus.RUNNING) execution.navigationId = result.actionId
        return CombatStep(result, retry = result.status !in setOf(NpcActionStatus.ACCEPTED, NpcActionStatus.RUNNING))
    }

    fun move(npc: NpcFacade, execution: TaskExecution, destination: NpcPosition, arrival: Double = 1.0): CombatStep {
        val snapshot = npc.snapshot()
        if (TaskNavigator.distanceSquared(snapshot.position, destination) <= arrival * arrival) {
            stopMoving(npc, execution)
            return CombatStep(NpcActionResult.succeeded("observed physical arrival"))
        }
        return approach(npc, snapshot, destination, execution, arrival)
    }

    fun release(npc: NpcFacade, execution: TaskExecution) {
        val snapshot = npc.snapshot()
        if (execution.combatActionId != null && snapshot.rangedAttack?.actionId == execution.combatActionId) npc.cancelRangedAttack()
        if (execution.tacticalUseId != null && snapshot.itemUse?.actionId == execution.tacticalUseId) npc.cancelItemUse()
        stopMoving(npc, execution)
        execution.combatActionId = null; execution.tacticalUseId = null; execution.approach = null
        execution.lastRetreatPlanTick = null; execution.lastProgressTick = null
    }

    private fun stopMoving(npc: NpcFacade, execution: TaskExecution) {
        val snapshot = npc.snapshot()
        if (snapshot.navigation != null || snapshot.control != null) npc.stopControl()
        clearRoute(execution)
        execution.lastProgressTick = snapshot.gameTime
    }
    private fun clearRoute(execution: TaskExecution) {
        execution.navigationId = null; execution.completion = null; execution.combatRoute = null
    }
    private fun running(detail: String) = CombatStep(NpcActionResult.running(detail))
    private val FAILURE = setOf(NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED)
    private val DIRECTIONS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, -1 to 1, 1 to -1, 1 to 1)
}