package io.samcnpc.behavior.task

import java.util.UUID

internal enum class TaskStatus {
    RUNNING, WAITING, PAUSED, COMPLETED, CANCELLED, FAILED;
    val terminal: Boolean get() = this == COMPLETED || this == CANCELLED || this == FAILED
}

internal enum class TaskReason {
    ASSIGNED, ARRIVED, USER_PAUSED, USER_RESUMED, USER_CANCELLED, INTERRUPTED, RESUMED,
    DESTINATION_UNAVAILABLE, NO_PROGRESS, RETRY_LIMIT, TIME_LIMIT, DIMENSION_CHANGED,
    STATE_MISMATCH, ASSIGNMENT_CHANGED, NPC_REMOVED, CHANNEL_UNAVAILABLE,
    DELIVERED, MISSING_RESOURCE, STORAGE_FULL, WORK_FAILED, SOURCE_EMPTY, SOURCE_UNAVAILABLE, INVENTORY_FULL,
    TARGET_DEFEATED, TARGET_ENDED, TARGET_UNAVAILABLE, LEASH_REACHED, PERMISSION_CHANGED,
    COMBAT_TIME_LIMIT, COMBAT_NO_PROGRESS, RECOVERY_EXHAUSTED,
    DEFENSE_FINISHED, AREA_CLEARED, PATROL_FINISHED, SUBJECT_UNAVAILABLE, INVENTORY_FINISHED, INVENTORY_INCOMPLETE, MINING_FINISHED, FOOD_FINISHED, FARM_FINISHED, PLANTING_FINISHED,
}

/** A suspended intent, not a serialized execution stack. All fields are bounded and world-free. */
internal class TaskFrame(
    val id: UUID,
    var definition: TaskDefinition,
    var remainingTicks: Int = definition.budget.ticks,
    var failures: Int = 0,
    var waitTicks: Int = 0,
    var reason: TaskReason = TaskReason.ASSIGNED,
    var resources: ResourceProgress? = null,
    var lumberjack: LumberjackTaskState? = null,
    var combat: CombatTaskState? = CombatTaskState.forDefinition(definition),
    var transport: TransportTaskState? = null,
    var inventory: InventoryWorkState? = null,
    var mining: MiningTaskState? = null,
    var food: FoodTaskState? = null,
    var farming: FarmTaskState? = null,
    var planting: PlantingTaskState? = null,
) {
    val status: TaskStatus get() = if (waitTicks > 0) TaskStatus.WAITING else TaskStatus.RUNNING
}

internal data class TaskReport(
    val taskId: UUID,
    val operationId: String,
    val status: TaskStatus,
    val reason: TaskReason,
    val detail: String,
    val remainingTicks: Int,
    val failures: Int,
    val interruptedDepth: Int,
    val completedInterruptions: Int,
    val lastCombat: TaskCombatOutcome?,
)

/** Pure finite lifecycle; world observation/execution and NBT encoding live outside this model. */
internal class TaskRecord(
    val npcUuid: UUID,
    val id: UUID,
    frames: List<TaskFrame>,
    previousPacks: List<String>,
    var status: TaskStatus = TaskStatus.RUNNING,
    var reason: TaskReason = TaskReason.ASSIGNED,
    var detail: String = "task assigned",
    var reconciledPosition: io.samcnpc.core.api.NpcPosition? = null,
    var totalFailures: Int = 0,
    var completedInterruptions: Int = 0,
    val reaction: TaskReactionState = TaskReactionState(),
    var lastCombat: TaskCombatOutcome? = null,
    val amendments: TaskAmendmentState = TaskAmendmentState(id),
    val logistics: TaskLogisticsState = TaskLogisticsState(),
) {
    val frames: MutableList<TaskFrame> = frames.toMutableList()
    val previousPacks: List<String> = java.util.List.copyOf(previousPacks)
    val active: TaskFrame get() = frames.last()
    val primary: TaskFrame get() = frames.first()

    init { require(frames.size in 1..MAX_FRAMES) { "task must contain one primary and at most two interruptions" } }

    fun pause(): Boolean {
        if (status.terminal || status == TaskStatus.PAUSED) return false
        status = TaskStatus.PAUSED
        reason = TaskReason.USER_PAUSED
        detail = "paused; completed world effects are retained"
        return true
    }

    fun resume(): Boolean {
        if (status != TaskStatus.PAUSED) return false
        status = active.status
        reason = TaskReason.USER_RESUMED
        detail = "resuming after fresh world observation"
        return true
    }

    fun interrupt(definition: TaskDefinition): String? {
        if (status.terminal || status == TaskStatus.PAUSED) return "only an active task can be interrupted"
        if (definition !is NavigateTaskDefinition && definition !is AttackTaskDefinition && definition !is InventoryTaskDefinition) return "only navigation, exact attack or bounded inventory interruptions are supported"
        if (definition is InventoryTaskDefinition && frames.size != 1) return "inventory side work starts only above the primary intent"
        if (definition is NavigateTaskDefinition && frames.any { it.definition is InventoryTaskDefinition }) return "finish inventory return before adding a navigation interruption"
        if (definition is AttackTaskDefinition && frames.any { it.definition is AttackTaskDefinition }) return "an active combat frame cannot be interrupted by another combat frame"
        if (frames.size >= MAX_FRAMES) return "task interruption depth is limited to two"
        if (completedInterruptions + frames.size - 1 >= 32) return "task interruption count is limited to 32"
        val problem = definition.validationProblem()
        if (problem != null) return problem
        if (definition.dimensionId != primary.definition.dimensionId) return "an interruption cannot change task dimension"
        frames.add(TaskFrame(UUID.randomUUID(), definition))
        status = TaskStatus.RUNNING
        reason = TaskReason.INTERRUPTED
        detail = "executing bounded interruption; primary intent retained"
        return null
    }

    fun completeActive(why: TaskReason = TaskReason.ARRIVED, message: String = "arrived at the requested position") {
        if (status.terminal || status == TaskStatus.PAUSED) return
        if (frames.size == 1) finish(TaskStatus.COMPLETED, why, message)
        else {
            completedInterruptions++
            frames.removeAt(frames.lastIndex)
            status = active.status
            reason = TaskReason.RESUMED
            detail = "interruption completed; revalidate the suspended destination"
        }
    }

    fun retry(failure: TaskReason, message: String) {
        if (status.terminal || status == TaskStatus.PAUSED) return
        val frame = active
        frame.failures++
        totalFailures++
        if (frame.failures >= frame.definition.budget.attempts) {
            if (frame.definition is InventoryTaskDefinition) endInventory(false, InventoryWorkReason.RETURN_FAILED, "inventory navigation retry limit after $failure: $message")
            else if (frame.definition is AttackTaskDefinition) endCombat(TaskStatus.FAILED, TaskReason.COMBAT_NO_PROGRESS, 0, "combat retry limit after $failure: $message")
            else finish(TaskStatus.FAILED, TaskReason.RETRY_LIMIT, "retry limit after $failure: $message")
            return
        }
        frame.reason = failure
        frame.waitTicks = (frame.definition.budget.backoffTicks * (1 shl (frame.failures - 1))).coerceAtMost(200)
        status = TaskStatus.WAITING
        reason = failure
        detail = message.take(MAX_DETAIL_LENGTH)
    }

    /** Active time includes waits and interruptions; pause never grants fresh attempts/time. */
    fun advanceTime(ticks: Int) {
        require(ticks >= 0)
        if (status.terminal || status == TaskStatus.PAUSED || ticks == 0) return
        reaction.cooldownRemaining = (reaction.cooldownRemaining - ticks).coerceAtLeast(0)
        logistics.cooldownRemaining = (logistics.cooldownRemaining - ticks).coerceAtLeast(0)
        for (frame in frames) {
            frame.remainingTicks = (frame.remainingTicks - ticks).coerceAtLeast(0)
            frame.farming?.let { state ->
                if (state.phase == FarmPhase.WAIT_GROWTH) {
                    state.growthRemaining=(state.growthRemaining-ticks).coerceAtLeast(0)
                    state.nextGrowthCheck=(state.nextGrowthCheck-ticks).coerceAtLeast(0)
                }
            }
            frame.inventory?.let { if (it.phase == InventoryWorkPhase.WORK) it.workRemaining = (it.workRemaining - ticks).coerceAtLeast(0) }
            frame.combat?.advance(ticks, frame.id == active.id && status == TaskStatus.RUNNING && frame.combat?.selectedTarget == null)
        }
        if (primary.remainingTicks == 0 && primary.definition is InventoryTaskDefinition && active.id == primary.id) {
            endInventory(false, InventoryWorkReason.TIME_LIMIT, "inventory task reached its original deadline")
            return
        }
        if (primary.remainingTicks == 0 || frames.any { it.remainingTicks == 0 && it.definition !is AttackTaskDefinition && it.definition !is InventoryTaskDefinition }) {
            finish(TaskStatus.FAILED, TaskReason.TIME_LIMIT, "task duration exhausted")
            return
        }
        if (active.remainingTicks == 0 && active.definition is InventoryTaskDefinition) {
            endInventory(false, InventoryWorkReason.TIME_LIMIT, "inventory interruption reached its original deadline; return unconfirmed")
            return
        }
        if (active.remainingTicks == 0 && active.definition is AttackTaskDefinition) {
            endCombat(TaskStatus.CANCELLED, TaskReason.COMBAT_TIME_LIMIT, 0, "combat interruption reached its original deadline")
            return
        }
        if (active.waitTicks > 0) {
            active.waitTicks = (active.waitTicks - ticks).coerceAtLeast(0)
            if (active.waitTicks == 0) {
                status = TaskStatus.RUNNING
                reason = TaskReason.RESUMED
                detail = "retry backoff ended; observe the destination again"
            }
        }
    }

    /** Ending a reaction resumes intent; it cannot turn a missing target into a confirmed kill. */
    fun endCombat(outcome: TaskStatus, why: TaskReason, kills: Int, message: String) {
        if (status.terminal || status == TaskStatus.PAUSED) return
        val definition = active.definition as? AttackTaskDefinition ?: error("no active combat frame")
        require(outcome.terminal && kills in 0..1)
        require((outcome == TaskStatus.COMPLETED) == (why == TaskReason.TARGET_DEFEATED))
        require(kills == if (why == TaskReason.TARGET_DEFEATED) 1 else 0)
        lastCombat = TaskCombatOutcome(definition.targetUuid, outcome, why, kills, message.take(MAX_DETAIL_LENGTH))
        if (frames.size == 1) { finish(outcome, why, message); return }
        completedInterruptions++
        if (reaction.activeFrame == active.id) reaction.activeFrame = null
        frames.removeAt(frames.lastIndex)
        reaction.cooldownRemaining = reaction.policy.cooldownTicks
        status = active.status
        reason = TaskReason.RESUMED
        detail = "combat ended ($why); revalidate and resume the suspended task"
        if (active.definition is InventoryTaskDefinition && active.remainingTicks == 0) {
            endInventory(false, InventoryWorkReason.TIME_LIMIT, "inventory deadline elapsed during combat; return unconfirmed")
        }
    }

    fun endInventory(returned: Boolean, why: InventoryWorkReason? = null, message: String) {
        if (status.terminal || status == TaskStatus.PAUSED) return
        val frame = active
        val definition = frame.definition as? InventoryTaskDefinition ?: error("no active inventory work")
        val state = checkNotNull(frame.inventory)
        state.detail = message.take(MAX_DETAIL_LENGTH)
        val outcome = state.outcome(frame.id, definition.work.kind, returned, why)
        require(logistics.outcomes.size < 32 && logistics.outcomes.none { it.frameId == frame.id })
        logistics.outcomes.add(outcome)
        if (frames.size == 1) {
            val complete = returned && outcome.reason == InventoryWorkReason.SATISFIED
            finish(if (complete) TaskStatus.COMPLETED else TaskStatus.FAILED,
                if (complete) TaskReason.INVENTORY_FINISHED else TaskReason.INVENTORY_INCOMPLETE, message)
            return
        }
        completedInterruptions++; frames.removeAt(frames.lastIndex)
        logistics.cooldownRemaining = logistics.policy.cooldownTicks
        status = active.status; reason = TaskReason.RESUMED
        detail = "inventory ${outcome.reason}; returned=$returned; resuming original intent without fresh time or retries"
    }

    fun finish(outcome: TaskStatus, why: TaskReason, message: String) {
        require(outcome.terminal)
        if (status.terminal) return
        for (frame in frames) {
            val inventory = frame.inventory ?: continue
            val definition = frame.definition as InventoryTaskDefinition
            if (logistics.outcomes.none { it.frameId == frame.id }) {
                inventory.detail = message.take(MAX_DETAIL_LENGTH)
                logistics.outcomes.add(inventory.outcome(frame.id, definition.work.kind, false,
                    if (why == TaskReason.STATE_MISMATCH) InventoryWorkReason.STATE_MISMATCH else if (why == TaskReason.TIME_LIMIT) InventoryWorkReason.TIME_LIMIT else InventoryWorkReason.CANCELLED))
            }
        }
        amendments.pending?.let { request ->
            amendments.receipts.add(TaskAmendmentReceipt(request, amendments.revision, TaskAmendmentOutcome.REJECTED, "task ended before amendment: $why"))
            amendments.pending = null
        }
        status = outcome
        reason = why
        detail = message.take(MAX_DETAIL_LENGTH)
    }

    fun report() = TaskReport(id, primary.definition.operationId, status, reason, detail,
        primary.remainingTicks, totalFailures, frames.size - 1, completedInterruptions, lastCombat)

    companion object {
        const val MAX_FRAMES = 3
        const val MAX_DETAIL_LENGTH = 256
        fun start(npcUuid: UUID, definition: TaskDefinition, previousPacks: List<String>): TaskRecord {
            require(definition.validationProblem() == null)
            return TaskRecord(npcUuid, UUID.randomUUID(), listOf(TaskFrame(UUID.randomUUID(), definition)), previousPacks)
        }
    }
}
