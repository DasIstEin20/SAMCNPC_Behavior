package io.samcnpc.behavior.lumberjack

import io.samcnpc.behavior.kernel.navigation.isClearPlayerStandingCell
import io.samcnpc.behavior.kernel.navigation.isLeafOrSupportedSnowObstacle
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.behavior.lumberjack.tree.isLumberjackLeafBlock
import io.samcnpc.behavior.lumberjack.tree.isLumberjackSnowLayer
import io.samcnpc.behavior.lumberjack.tree.isLumberjackWoodLog
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcLookRotation
import io.samcnpc.core.api.NpcNavigationRequest
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcSnapshot
import io.samcnpc.core.api.NpcRaycastRequest
import io.samcnpc.core.api.NpcRaycastResult
import io.samcnpc.core.api.NpcVector
import io.samcnpc.core.api.NpcWorldView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Reusable player-like spatial primitives used by lumberjack phases. Tree selection and phase
 * transitions remain in [LumberjackService]; this helper owns only poses, stances and bounded
 * collision/raycast geometry.
 */
internal fun moveToward(
    npc: NpcFacade,
    target: NpcBlockPosition,
    arrivalDistance: Double,
    targetMode: MoveTarget = MoveTarget.BLOCK_SIDE,
): MoveTowardProgress {
    val snapshot = npc.snapshot()
    val arrived = when (targetMode) {
        // After mining, navigation correctly targets the former block's base. Measuring to its
        // geometric center adds a false half-block vertical gap to an NPC on the floor.
        MoveTarget.CLEARED_BLOCK -> isWithinPosition(snapshot.position, target, arrivalDistance)
        MoveTarget.BLOCK_SIDE -> isWithinDistance(snapshot.position, target, arrivalDistance)
    }
    if (arrived) {
        return MoveTowardProgress.ARRIVED
    }
    return navigateAndLook(npc, navigationPosition(snapshot.position, target, targetMode), blockCenter(target))
}

internal fun moveToMiningStance(
    npc: NpcFacade,
    target: NpcBlockPosition,
    stance: NpcBlockPosition,
): MoveTowardProgress {
    if (isWithinPosition(npc.snapshot().position, stance, MINING_STANCE_ARRIVAL_DISTANCE)) {
        return MoveTowardProgress.ARRIVED
    }
    return navigateAndLook(npc, blockNavigationPosition(stance), blockCenter(target))
}

internal fun navigateAndLook(
    npc: NpcFacade,
    navigationTarget: NpcPosition,
    lookTarget: NpcPosition,
): MoveTowardProgress {
    // A work stance needs closer approach than the general navigation default. Otherwise the
    // body can stop on the edge of a higher block before stepping down into its supplied cell.
    val navigation = npc.navigateTo(NpcNavigationRequest(
        navigationTarget, NAVIGATION_SPEED_MULTIPLIER, arrivalDistance = WORK_NAVIGATION_ARRIVAL_DISTANCE,
    ))
    if (navigation.isFailure()) {
        return MoveTowardProgress.FAILED(navigation)
    }
    val look = lookTowards(npc, lookTarget)
    if (look.isFailure()) {
        return MoveTowardProgress.FAILED(look)
    }
    return MoveTowardProgress.MOVING
}

internal fun lookTowards(npc: NpcFacade, lookTarget: NpcPosition): NpcActionResult {
    val snapshot = npc.snapshot()
    val dx = lookTarget.x - snapshot.position.x
    val dy = lookTarget.y - snapshot.position.y
    val dz = lookTarget.z - snapshot.position.z
    val horizontal = hypot(dx, dz)
    val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
    val pitch = if (horizontal <= 0.0001) snapshot.pitch else (-Math.toDegrees(atan2(dy, horizontal))).toFloat()
    return npc.setLookRotation(NpcLookRotation(yaw, pitch))
}

internal fun selectMiningStance(
    origin: NpcPosition,
    trunkBase: NpcBlockPosition,
    world: NpcWorldView,
    excluded: Set<NpcBlockPosition> = emptySet(),
): NpcBlockPosition? {
    fun nearest(candidates: List<NpcBlockPosition>): NpcBlockPosition? = candidates.asSequence()
        .filter { it !in excluded && world.isClearPlayerStandingCell(it) }
        .minWithOrNull(compareBy<NpcBlockPosition> { candidate ->
            distanceSquared(origin, blockNavigationPosition(candidate))
        }.thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
    val normal = nearest(miningStanceCandidates(trunkBase))
    if (normal != null) return normal
    // Bent crowns have detached vertical columns. Their first log is not ground height;
    // use only observed supported feet cells, within the existing eight-level elevation bound.
    val lower = (1..8).flatMap { depth -> miningStanceCandidates(trunkBase.copy(y = trunkBase.y - depth)) }
    return nearest(lower)
}

internal fun rememberRejectedStance(job: LumberjackDemoJob, stance: NpcBlockPosition) {
    if (stance !in job.rejectedMiningStances && job.rejectedMiningStances.size < MAX_REJECTED_MINING_STANCES) {
        job.rejectedMiningStances.add(stance)
    }
}

internal fun rejectedStances(
    job: LumberjackDemoJob,
    additional: NpcBlockPosition? = null,
): Set<NpcBlockPosition> {
    val rejected = job.rejectedMiningStances.toMutableSet()
    if (additional != null) {
        rejected.add(additional)
    }
    return rejected
}

internal fun LumberjackDemoJob.canClimbOntoPreservedStump(world: NpcWorldView): Boolean {
    val trunkBase = trunkBasePosition ?: return false
    val target = targetPosition ?: return false
    if (target.y <= trunkBase.y) {
        return false
    }
    val stumpTop = NpcBlockPosition(trunkBase.x, trunkBase.y + 1, trunkBase.z)
    return world.observeBlock(trunkBase)?.isLumberjackWoodLog() == true && world.observeBlock(stumpTop)?.isAir == true
}

/** A jump-height sample is not a landing; only actual grounded support can finish the step. */
internal fun LumberjackDemoJob.isStandingOnPreservedStump(snapshot: NpcSnapshot): Boolean {
    val trunkBase = trunkBasePosition ?: return false
    val stumpTop = NpcBlockPosition(trunkBase.x, trunkBase.y + 1, trunkBase.z)
    return miningStance == stumpTop && isStandingOnPreservedStump(snapshot.position, snapshot.onGround, trunkBase)
}

internal fun isStandingOnPreservedStump(position: NpcPosition, onGround: Boolean, trunkBase: NpcBlockPosition): Boolean =
    onGround && abs(position.y - trunkBase.y - 1.0) <= STUMP_TOP_HEIGHT_TOLERANCE &&
        abs(position.x - trunkBase.x - 0.5) <= STUMP_TOP_HALF_EXTENT &&
        abs(position.z - trunkBase.z - 0.5) <= STUMP_TOP_HALF_EXTENT

internal fun LumberjackDemoJob.recordedScaffoldStance(snapshot: NpcSnapshot): NpcBlockPosition? {
    if (!snapshot.onGround) return null
    val feet = NpcBlockPosition(floor(snapshot.position.x).toInt(), floor(snapshot.position.y).toInt(), floor(snapshot.position.z).toInt())
    val support = NpcBlockPosition(feet.x, feet.y - 1, feet.z)
    return feet.takeIf { support in pillarSession?.placedPositions.orEmpty() }
}

internal fun canSeeWorkBlock(npc: NpcFacade, target: NpcBlockPosition, world: NpcWorldView): Boolean {
    val eye = npc.snapshot().eyePosition
    val center = blockCenter(target)
    val direction = NpcVector(center.x - eye.x, center.y - eye.y, center.z - eye.z)
    val length = hypot(hypot(direction.x, direction.z), direction.y)
    return (world.raycast(NpcRaycastRequest(eye, direction, length + RAYCAST_TARGET_EPSILON)) as? NpcRaycastResult.BlockHit)?.position == target
}

/**
 * Returns the first foliage cell in the NPC's feet/head collision corridor along either
 * horizontal axis toward the selected navigation goal. It ignores vertical space so a canopy
 * above a valid route never becomes a fake path obstruction.
 */
internal fun foliageObstacleOnNavigationAxes(
    npc: NpcFacade,
    destination: NpcPosition,
    world: NpcWorldView,
    visibleOnly: Boolean = false,
    includeLogs: Boolean = false,
): NpcBlockPosition? {
    val position = npc.snapshot().position
    val startX = floor(position.x).toInt()
    val startY = floor(position.y).toInt()
    val startZ = floor(position.z).toInt()
    val deltaX = destination.x - position.x
    val deltaZ = destination.z - position.z
    val axes = mutableListOf<NavigationAxis>()
    if (abs(deltaX) >= MIN_AXIS_ROUTE_DELTA) {
        axes.add(NavigationAxis(if (deltaX > 0.0) 1 else -1, 0, ceil(abs(deltaX)).toInt()))
    }
    if (abs(deltaZ) >= MIN_AXIS_ROUTE_DELTA) {
        axes.add(NavigationAxis(0, if (deltaZ > 0.0) 1 else -1, ceil(abs(deltaZ)).toInt()))
    }
    axes.sortByDescending { axis -> axis.steps }
    for (axis in axes) {
        for (step in 0..axis.steps.coerceAtMost(MAX_FOLIAGE_AXIS_PROBE_STEPS)) {
            val x = startX + axis.stepX * step
            val z = startZ + axis.stepZ * step
            for (y in startY until startY + NPC_COLLISION_HEIGHT_BLOCKS) {
                val candidate = NpcBlockPosition(x, y, z)
                if (world.isLeafOrSupportedSnowObstacle(candidate) || (includeLogs && world.observeBlock(candidate)?.isLumberjackWoodLog() == true)) {
                    if (!visibleOnly) return candidate
                    // Feet-level foliage can be hidden by a head-level leaf or a solid trunk.
                    // A chest route has no tree-work continuation: select an immediately legal
                    // clearance of the first allowed block on this same corridor ray.
                    val visible = accessObstacleOnRay(npc, blockCenter(candidate), null, world, includeLogs) ?: continue
                    if (isWithinDistance(position, visible, 4.5) && canSeeWorkBlock(npc, visible, world)) return visible
                }
            }
        }
    }
    return null
}

internal fun selectFoliageClearingStance(
    origin: NpcPosition,
    obstacle: NpcBlockPosition,
    world: NpcWorldView,
): NpcBlockPosition? = listOf(obstacle.y - 1, obstacle.y, obstacle.y - 2)
    .flatMap { y ->
        listOf(
            NpcBlockPosition(obstacle.x - 1, y, obstacle.z),
            NpcBlockPosition(obstacle.x + 1, y, obstacle.z),
            NpcBlockPosition(obstacle.x, y, obstacle.z - 1),
            NpcBlockPosition(obstacle.x, y, obstacle.z + 1),
        )
    }
    .asSequence()
    .distinct()
    .filter { candidate -> world.isClearPlayerStandingCell(candidate) }
    .minWithOrNull(
        compareBy<NpcBlockPosition> { candidate -> distanceSquared(origin, blockNavigationPosition(candidate)) }
            .thenBy { it.x }
            .thenBy { it.y }
            .thenBy { it.z },
    )

internal fun isWithinDirectFoliageBreakRange(position: NpcPosition, target: NpcBlockPosition): Boolean =
    distanceSquared(position, blockCenter(target)) <= MAX_DIRECT_FOLIAGE_BREAK_DISTANCE_SQR

/** An access block is removed only when this exact eye ray confirms it blocks the supplied log. */
internal fun accessObstacleBlockingViewOf(
    npc: NpcFacade,
    target: NpcBlockPosition,
    world: NpcWorldView,
): NpcBlockPosition? = accessObstacleOnRay(npc, blockCenter(target), target, world)

internal fun accessObstacleOnRay(
    npc: NpcFacade,
    destination: NpcPosition,
    ignoredPosition: NpcBlockPosition?,
    world: NpcWorldView,
    includeLogs: Boolean = false,
): NpcBlockPosition? {
    val eye = npc.snapshot().eyePosition
    val direction = NpcVector(destination.x - eye.x, destination.y - eye.y, destination.z - eye.z)
    val distance = hypot(hypot(direction.x, direction.z), direction.y)
    if (distance <= MIN_RAYCAST_DISTANCE) {
        return null
    }
    val hit = world.raycast(NpcRaycastRequest(eye, direction, distance + RAYCAST_TARGET_EPSILON)) as? NpcRaycastResult.BlockHit
        ?: return null
    if (hit.position == ignoredPosition) {
        return null
    }
    val observation = world.observeBlock(hit.position) ?: return null
    return hit.position.takeIf { candidate ->
        (includeLogs && observation.isLumberjackWoodLog()) || observation.isLumberjackLeafBlock() ||
            (observation.isLumberjackSnowLayer() &&
                world.observeBlock(NpcBlockPosition(candidate.x, candidate.y - 1, candidate.z))?.isLumberjackLeafBlock() == true)
    }
}

internal fun leafInSameBoundedTreeVolume(log: NpcBlockPosition, leaf: NpcBlockPosition): Boolean =
    abs(log.x - leaf.x) <= MAX_LEAF_INTERVENTION_RADIUS &&
        abs(log.y - leaf.y) <= MAX_LEAF_INTERVENTION_RADIUS &&
        abs(log.z - leaf.z) <= MAX_LEAF_INTERVENTION_RADIUS

internal fun navigationPosition(
    origin: NpcPosition,
    target: NpcBlockPosition,
    targetMode: MoveTarget,
): NpcPosition = when (targetMode) {
    // Once Core destroyed the log this block space is air. Its base is the walkable pickup
    // position; targeting the former top would ask ground navigation to stand in air.
    MoveTarget.CLEARED_BLOCK -> NpcPosition(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
    MoveTarget.BLOCK_SIDE -> listOf(
        NpcPosition(target.x - 0.5, target.y + 1.0, target.z + 0.5),
        NpcPosition(target.x + 1.5, target.y + 1.0, target.z + 0.5),
        NpcPosition(target.x + 0.5, target.y + 1.0, target.z - 0.5),
        NpcPosition(target.x + 0.5, target.y + 1.0, target.z + 1.5),
    ).minBy { candidate ->
        val dx = candidate.x - origin.x
        val dz = candidate.z - origin.z
        dx * dx + dz * dz
    }
}

internal fun blockNavigationPosition(position: NpcBlockPosition): NpcPosition =
    NpcPosition(position.x + 0.5, position.y.toDouble(), position.z + 0.5)

internal fun blockCenter(position: NpcBlockPosition): NpcPosition =
    NpcPosition(position.x + 0.5, position.y + 0.5, position.z + 0.5)

internal fun isWithinDistance(position: NpcPosition, target: NpcBlockPosition, distance: Double): Boolean {
    val dx = target.x + 0.5 - position.x
    val dy = target.y + 0.5 - position.y
    val dz = target.z + 0.5 - position.z
    return dx * dx + dy * dy + dz * dz <= distance * distance
}

internal fun isWithinPosition(position: NpcPosition, target: NpcBlockPosition, distance: Double): Boolean {
    val targetPosition = blockNavigationPosition(target)
    val dx = targetPosition.x - position.x
    val dy = targetPosition.y - position.y
    val dz = targetPosition.z - position.z
    return dx * dx + dy * dy + dz * dz <= distance * distance
}

internal fun horizontalDistance(position: NpcPosition, target: NpcBlockPosition): Double =
    hypot(position.x - target.x - 0.5, position.z - target.z - 0.5)

internal fun horizontalDistanceSquared(first: NpcPosition, second: NpcPosition): Double {
    val dx = first.x - second.x
    val dz = first.z - second.z
    return dx * dx + dz * dz
}

internal fun distanceSquared(first: NpcPosition, second: NpcPosition): Double {
    val dx = first.x - second.x
    val dy = first.y - second.y
    val dz = first.z - second.z
    return dx * dx + dy * dy + dz * dz
}

internal sealed interface MoveTowardProgress {
    data object ARRIVED : MoveTowardProgress
    data object MOVING : MoveTowardProgress
    data class FAILED(val result: NpcActionResult) : MoveTowardProgress
}

internal enum class MoveTarget {
    BLOCK_SIDE,
    CLEARED_BLOCK,
}

private fun miningStanceCandidates(trunkBase: NpcBlockPosition): List<NpcBlockPosition> = listOf(
    NpcBlockPosition(trunkBase.x - MINING_STANCE_OFFSET, trunkBase.y, trunkBase.z),
    NpcBlockPosition(trunkBase.x + MINING_STANCE_OFFSET, trunkBase.y, trunkBase.z),
    NpcBlockPosition(trunkBase.x, trunkBase.y, trunkBase.z - MINING_STANCE_OFFSET),
    NpcBlockPosition(trunkBase.x, trunkBase.y, trunkBase.z + MINING_STANCE_OFFSET),
)

private fun NpcActionResult.isFailure(): Boolean = status in setOf(
    NpcActionStatus.REJECTED,
    NpcActionStatus.FAILED,
    NpcActionStatus.UNSUPPORTED,
)

private data class NavigationAxis(val stepX: Int, val stepZ: Int, val steps: Int)

private const val WORK_NAVIGATION_ARRIVAL_DISTANCE = 0.5
private const val MINING_STANCE_OFFSET = 2
// Navigation settles a Mob around the requested cell rather than exactly at its centre. This
// still leaves the hull clear of the trunk at the two-cell stance offset; Core remains the
// authority on physical reach and eye ray before an explicit break starts.
internal const val MINING_STANCE_ARRIVAL_DISTANCE = 1.0
internal const val NAVIGATION_SPEED_MULTIPLIER = 1.3F
private const val STUMP_TOP_HEIGHT_TOLERANCE = 0.05
// The 0.6-wide player-like body may overlap a top edge, but not stand a full block beside it.
// A small overlap margin avoids accepting a hull that only touches the stump's collision face.
private const val STUMP_TOP_HALF_EXTENT = 0.75
private const val MAX_REJECTED_MINING_STANCES = 4
private const val MAX_LEAF_INTERVENTION_RADIUS = 6
private const val MIN_AXIS_ROUTE_DELTA = 0.05
private const val MAX_FOLIAGE_AXIS_PROBE_STEPS = 6
private const val NPC_COLLISION_HEIGHT_BLOCKS = 2
private const val MAX_DIRECT_FOLIAGE_BREAK_DISTANCE_SQR = 5.0 * 5.0
private const val MIN_RAYCAST_DISTANCE = 0.0001
private const val RAYCAST_TARGET_EPSILON = 0.01
