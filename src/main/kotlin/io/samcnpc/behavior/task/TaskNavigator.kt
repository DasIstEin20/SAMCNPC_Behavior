package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import io.samcnpc.behavior.kernel.navigation.LocalObstructionDetour
import io.samcnpc.behavior.kernel.navigation.PassageYielding
import java.util.UUID

internal class TaskExecution(val taskId: UUID, val frameId: UUID) {
    val generation: UUID = UUID.randomUUID()
    val obstructionDetour = LocalObstructionDetour()
    val rejectedWorkStances = linkedSetOf<NpcBlockPosition>()
    var blockToolId: String? = null
    var blockActionId: UUID? = null
    var blockCompletion: NpcActionResult? = null
    var navigationId: UUID? = null
    var completion: NpcActionResult? = null
    var bestDistanceSquared = Double.POSITIVE_INFINITY
    var lastProgressTick: Long? = null
    var approach: NpcPosition? = null
    var lastRepathTick: Long? = null
    var combatRoute: NpcNavigationRequest? = null
    var combatActionId: UUID? = null
    var tacticalUseId: UUID? = null
    var fishingActionId: UUID? = null
    var lastEquipmentTick: Long? = null
    var lastRetreatPlanTick: Long? = null
}

/** A failed leg is an observation; the executor decides whether to retry the job or reject that leg. */
internal sealed interface TaskNavigationStep {
    data object Arrived : TaskNavigationStep
    data class Progress(val action: NpcActionResult) : TaskNavigationStep
    data class Failed(val reason: TaskReason, val detail: String, val action: NpcActionResult? = null) : TaskNavigationStep
}

/** One bounded navigation intent. Every attempt starts from fresh physical facts. */
internal object TaskNavigator {
    /** Detach the old handle before Core synchronously publishes its cancellation. */
    fun stop(execution: TaskExecution, npc: NpcFacade) {
        execution.navigationId = null
        execution.completion = null
        execution.approach = null
        execution.bestDistanceSquared = Double.POSITIVE_INFINITY
        execution.lastProgressTick = null
        execution.lastRepathTick = null
        npc.stopControl()
    }

    fun tick(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val definition = record.active.definition as NavigateTaskDefinition
        val result = move(record, execution, npc, world, definition)
        if (result.status == NpcActionStatus.SUCCEEDED && record.status == TaskStatus.RUNNING) record.completeActive()
        return result
    }

    fun move(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView, definition: NavigateTaskDefinition, bounds: NpcNavigationBounds? = null): NpcActionResult {
        return when (val outcome = step(record, execution, npc, world, definition, bounds)) {
            TaskNavigationStep.Arrived -> NpcActionResult.succeeded("arrived at supplied standing position")
            is TaskNavigationStep.Progress -> {
                val action = outcome.action
                // A successful sub-mechanism is not evidence of the task's physical arrival.
                if (action.status == NpcActionStatus.SUCCEEDED) NpcActionResult.running(action.detail, action.actionId, action.channel) else action
            }
            is TaskNavigationStep.Failed -> {
                if (outcome.reason == TaskReason.DIMENSION_CHANGED) {
                    record.finish(TaskStatus.FAILED, outcome.reason, outcome.detail)
                    NpcActionResult.failed(outcome.detail, NpcActionCode.WORLD_REJECTED)
                } else {
                    record.retry(outcome.reason, outcome.detail)
                    outcome.action ?: NpcActionResult.running(record.detail)
                }
            }
        }
    }

    fun step(
        record: TaskRecord,
        execution: TaskExecution,
        npc: NpcFacade,
        world: NpcWorldView,
        definition: NavigateTaskDefinition,
        bounds: NpcNavigationBounds? = null,
    ): TaskNavigationStep {
        val snapshot = npc.snapshot()
        if (snapshot.dimensionId != definition.dimensionId || world.dimensionId != definition.dimensionId) {
            return TaskNavigationStep.Failed(TaskReason.DIMENSION_CHANGED, "NPC is outside the task dimension")
        }
        val standing = world.observeStandingSpace(definition.destination)
        if (standing == null || !standing.clear || !standing.supported || standing.inFluid) {
            return TaskNavigationStep.Failed(TaskReason.DESTINATION_UNAVAILABLE, "destination is unavailable, obstructed, unsupported or in fluid")
        }
        record.reconciledPosition = snapshot.position
        val distanceSquared = distanceSquared(snapshot.position, definition.destination)
        if (distanceSquared <= definition.arrivalDistance * definition.arrivalDistance && snapshot.onGround) {
            return TaskNavigationStep.Arrived
        }
        val request = NpcNavigationRequest(definition.destination, definition.speed, definition.arrivalDistance, bounds = bounds)
        if (bounds == null) {
            // Direct-control recovery has no route-envelope primitive. Bounded legs therefore
            // use native paths only; a candidate filter cannot silently authorize an outside step.
            val yielding = PassageYielding.tick(record.id, npc, world, request) { candidate -> permittedDetour(record, candidate) }
            when (yielding) {
                PassageYielding.Result.Proceed -> Unit
                is PassageYielding.Result.Handling -> {
                    execution.lastProgressTick = snapshot.gameTime
                    execution.navigationId = null; execution.completion = null
                    record.detail = yielding.action.detail.take(TaskRecord.MAX_DETAIL_LENGTH)
                    return TaskNavigationStep.Progress(yielding.action)
                }
                is PassageYielding.Result.Failed -> {
                    execution.navigationId = null; execution.completion = null
                    return TaskNavigationStep.Failed(TaskReason.NO_PROGRESS, yielding.detail)
                }
            }
            val detour = execution.obstructionDetour.tick(npc, world, request) { candidate -> permittedDetour(record, candidate) }
            if (detour != null) {
                execution.lastProgressTick = snapshot.gameTime
                execution.navigationId = null; execution.completion = null
                record.detail = detour.detail.take(TaskRecord.MAX_DETAIL_LENGTH)
                return TaskNavigationStep.Progress(detour)
            }
        }
        val completion = execution.completion
        execution.completion = null
        if (completion != null) {
            return TaskNavigationStep.Failed(TaskReason.NO_PROGRESS, "navigation ended before physical arrival: ${completion.code}: ${completion.detail}")
        }
        val now = snapshot.gameTime
        if (execution.lastProgressTick == null || distanceSquared < execution.bestDistanceSquared - 0.0625) {
            execution.bestDistanceSquared = distanceSquared
            execution.lastProgressTick = now
        }
        val lastProgress = checkNotNull(execution.lastProgressTick)
        if (now < lastProgress || now - lastProgress >= 100) {
            return TaskNavigationStep.Failed(TaskReason.NO_PROGRESS, "navigation made no measurable progress for 100 ticks")
        }
        val result = npc.navigateTo(request)
        return when (result.status) {
            NpcActionStatus.ACCEPTED, NpcActionStatus.RUNNING -> {
                execution.navigationId = result.actionId
                TaskNavigationStep.Progress(result)
            }
            NpcActionStatus.SUCCEEDED -> TaskNavigationStep.Failed(TaskReason.NO_PROGRESS, "Core route ended outside the task's physical arrival envelope")
            NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED -> TaskNavigationStep.Failed(TaskReason.NO_PROGRESS, result.detail, result)
        }
    }

    private fun permittedDetour(record: TaskRecord, candidate: NpcPosition): Boolean = when (val definition = if (record.active.definition is InventoryTaskDefinition) record.active.definition else record.primary.definition) {
        is ExplorerTaskDefinition -> definition.bounds.contains(candidate)
        is FishingTaskDefinition -> definition.contains(candidate)
        is MachineTaskDefinition -> definition.contains(candidate)
        is PlantingTaskDefinition -> definition.contains(candidate)
        is FarmTaskDefinition -> definition.contains(candidate)
        is FoodTaskDefinition -> definition.contains(candidate)
        is MiningTaskDefinition -> definition.contains(candidate)
        is InventoryTaskDefinition -> definition.contains(candidate)
        is TransportTaskDefinition -> definition.contains(candidate)
        is DeliveryTaskDefinition -> definition.version == 1 || CargoRoute.from(definition).contains(candidate)
        is LumberjackTaskDefinition -> record.primary.lumberjack?.replantDefinition?.contains(candidate) ?: definition.area.contains(NpcBlockPosition(kotlin.math.floor(candidate.x).toInt(), candidate.y.toInt(), kotlin.math.floor(candidate.z).toInt()))
        is CombatMissionDefinition -> definition.area().contains(candidate)
        is AttackTaskDefinition -> io.samcnpc.behavior.combat.CombatTargetSelector.Area(definition.anchor, definition.leash).contains(candidate)
        is NavigateTaskDefinition -> true
    }

    fun distanceSquared(first: NpcPosition, second: NpcPosition): Double {
        val x = first.x - second.x
        val y = first.y - second.y
        val z = first.z - second.z
        return x * x + y * y + z * z
    }
}
