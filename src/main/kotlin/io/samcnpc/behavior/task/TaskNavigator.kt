package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import io.samcnpc.behavior.kernel.navigation.PassageYielding
import java.util.UUID

internal class TaskExecution(val taskId: UUID, val frameId: UUID) {
    val generation: UUID = UUID.randomUUID()
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
    var lastEquipmentTick: Long? = null
    var lastRetreatPlanTick: Long? = null
}

/** One bounded navigation intent. Every attempt starts from fresh physical facts. */
internal object TaskNavigator {
    fun tick(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val definition = record.active.definition as NavigateTaskDefinition
        val result = move(record, execution, npc, world, definition)
        if (result.status == NpcActionStatus.SUCCEEDED && record.status == TaskStatus.RUNNING) record.completeActive()
        return result
    }

    fun move(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView, definition: NavigateTaskDefinition): NpcActionResult {
        val snapshot = npc.snapshot()
        if (snapshot.dimensionId != definition.dimensionId || world.dimensionId != definition.dimensionId) {
            record.finish(TaskStatus.FAILED, TaskReason.DIMENSION_CHANGED, "NPC is outside the task dimension")
            return NpcActionResult.failed(record.detail, NpcActionCode.WORLD_REJECTED)
        }
        val standing = world.observeStandingSpace(definition.destination)
        if (standing == null || !standing.clear || !standing.supported || standing.inFluid) {
            record.retry(TaskReason.DESTINATION_UNAVAILABLE, "destination is unavailable, obstructed, unsupported or in fluid")
            return NpcActionResult.running(record.detail)
        }
        record.reconciledPosition = snapshot.position
        val distanceSquared = distanceSquared(snapshot.position, definition.destination)
        if (distanceSquared <= definition.arrivalDistance * definition.arrivalDistance && snapshot.onGround) {
            return NpcActionResult.succeeded("arrived at supplied standing position")
        }
        val request = NpcNavigationRequest(definition.destination, definition.speed, definition.arrivalDistance)
        val yielding = PassageYielding.tick(record.id, npc, world, request) { candidate -> permittedDetour(record, candidate) }
        when (yielding) {
            PassageYielding.Result.Proceed -> Unit
            is PassageYielding.Result.Handling -> {
                // The separate finite yield clock bounds intentional waiting. The primary
                // task deadline still advances; do not call this a stalled original path.
                execution.lastProgressTick = snapshot.gameTime
                execution.navigationId = null; execution.completion = null
                record.detail = yielding.action.detail.take(TaskRecord.MAX_DETAIL_LENGTH)
                return yielding.action
            }
            is PassageYielding.Result.Failed -> {
                execution.navigationId = null; execution.completion = null
                record.retry(TaskReason.NO_PROGRESS, yielding.detail)
                return NpcActionResult.running(record.detail)
            }
        }
        val completion = execution.completion
        execution.completion = null
        if (completion != null) {
            record.retry(TaskReason.NO_PROGRESS, "navigation ended before physical arrival: ${completion.code}")
            return NpcActionResult.running(record.detail)
        }
        val now = snapshot.gameTime
        if (execution.lastProgressTick == null || distanceSquared < execution.bestDistanceSquared - 0.0625) {
            execution.bestDistanceSquared = distanceSquared
            execution.lastProgressTick = now
        }
        val lastProgress = checkNotNull(execution.lastProgressTick)
        if (now < lastProgress || now - lastProgress >= 100) {
            record.retry(TaskReason.NO_PROGRESS, "navigation made no measurable progress for 100 ticks")
            return NpcActionResult.running(record.detail)
        }
        val result = npc.navigateTo(request)
        when (result.status) {
            NpcActionStatus.ACCEPTED, NpcActionStatus.RUNNING -> execution.navigationId = result.actionId
            NpcActionStatus.SUCCEEDED -> {
                // Core's mechanical arrival envelope can differ vertically; verify our full goal
                // before accepting it. The next tick either sees physical arrival or retries.
                record.retry(TaskReason.NO_PROGRESS, "Core route ended outside the task's physical arrival envelope")
            }
            NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED -> record.retry(TaskReason.NO_PROGRESS, result.detail)
        }
        return result
    }

    private fun permittedDetour(record: TaskRecord, candidate: NpcPosition): Boolean = when (val definition = if (record.active.definition is InventoryTaskDefinition) record.active.definition else record.primary.definition) {
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
