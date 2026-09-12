package io.samcnpc.behavior.lumberjack.model

import io.samcnpc.behavior.kernel.elevation.TemporaryPillarSession
import io.samcnpc.core.api.NpcBlockPosition
import java.util.UUID

/** Durable policy state; persistence lives separately so later work actions can adopt this shape. */
internal data class LumberjackDemoJob(
    val npcUuid: UUID,
    val dimensionId: String,
    var chestPosition: NpcBlockPosition,
    val workCenter: NpcBlockPosition,
    val previousPackIds: List<String>,
    var phase: LumberjackDemoPhase,
    var scanCursor: Int,
    var pickupTicks: Int,
    /** True only for a capacity-triggered chest run; after deposit the bounded work resumes. */
    var resumeWorkAfterDeposit: Boolean,
    var targetPosition: NpcBlockPosition?,
    /** Lowest log of the one trunk currently being planned by this Behavior-only demo. */
    var trunkBasePosition: NpcBlockPosition?,
    /** The upper work target suspended while a lower step or material recovery is completed. */
    var blockedLogPosition: NpcBlockPosition?,
    /** A Behavior-selected clear standing cell for the current supplied log; never a Core intent. */
    var miningStance: NpcBlockPosition?,
    /** Bounded task-local route failures. */
    val rejectedMiningStances: MutableList<NpcBlockPosition>,
    var initialTrunkTargetPending: Boolean,
    var climbJumpAttempts: Int,
    /** In-memory post-launch movement commitment; stale movement is never persisted. */
    var climbForwardTicks: Int,
    /** The preserved base is being converted into normal wood drops for emergency scaffolding. */
    var scaffoldMaterialRecovery: Boolean,
    /** A non-recovery wood scaffold ran dry and must convert the retained stump into real drops. */
    var scaffoldMaterialRecoveryPending: Boolean,
    var failedWorkAttempts: Int,
    /** A failed or contested elevation session must clean up, then yield this tree rather than retry it. */
    var abandonTreeAfterPillarCleanup: Boolean,
    val initialWoodCounts: Map<String, Int>,
    var pickupQuietTicks: Int = 0,
    var scaffoldRecoveryAttempts: Int = 0,
    /** A separate, one-level continuation: clearing foliage must not overwrite material recovery. */
    var accessReturnTarget: NpcBlockPosition? = null,
    /** Behavior-owned durable elevation state. Core never observes or interprets this policy. */
    var pillarSession: TemporaryPillarSession? = null,
    var chestAccessTarget: NpcBlockPosition? = null,
    var chestAccessTicks: Int = 0,
    var chestAccessAttempts: Int = 0,
    /** Collect the route log's real drops before resuming a possibly suspended tree task. */
    var chestAccessStage: LumberjackChestAccessStage = LumberjackChestAccessStage.CLEAR_FOLIAGE,
    var chestAccessQuietTicks: Int = 0,
    /** Cached route endpoint; reselected from current geometry after reload, never persisted. */
    var chestApproach: NpcBlockPosition? = null,
    /** Transient handoff: the enclosing task/store consumes it in the same selected step. */
    var executionFinished: Boolean = false,
    /** Rebound from the parent task definition after load; legacy jobs keep their old scan. */
    var workSelection: LumberjackWorkSelection? = null,
    /** Recomputed from actual delivered/carried stock by the finite parent task. */
    var finishAfterCurrentTree: Boolean = false,
    var scanUnavailable: Boolean = false,
    /** Rebound by a v2 parent; legacy jobs retain their explicitly combined chest contract. */
    var externalSuppliesAllowed: Boolean = true,
    /** Finite tasks persist this bounded queue in their parent; the legacy demo leaves it disabled. */
    val deferredWood: MutableList<LumberjackDeferredTarget> = mutableListOf(),
    var deferredWoodEnabled: Boolean = false,
    /** Rebound by a finite parent; a lease can never outlive or impersonate another task. */
    var workTaskId: UUID = npcUuid,
)

internal enum class LumberjackChestAccessStage { CLEAR_FOLIAGE, CUT_WOOD, COLLECT_WOOD }

internal enum class LumberjackDemoPhase {
    TRAVEL_TO_CHEST,
    PREPARE_EQUIPMENT,
    SEARCH_WOOD,
    TRAVEL_TO_LOG,
    BREAK_LOG,
    CLIMB_TRUNK,
    COLLECT_LOG_DROP,
    COLLECT_TREE_DROPS,
    RETURN_TO_CHEST,
    DEPOSIT_WOOD,
    PILLAR_UP,
    PILLAR_CLEANUP,
}

internal data class LumberjackDeferredTarget(
    val target: NpcBlockPosition,
    val targetBlockId: String,
    val obstruction: NpcBlockPosition,
    val obstructionBlockId: String,
    val failedAttempts: Int,
    var revisited: Boolean = false,
)
