package io.samcnpc.behavior.lumberjack.tree

import io.samcnpc.core.api.NpcBlockObservation
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcWorldView

/**
 * Read-only facts about the single vertical trunk shape supported by the initial lumberjack.
 * It contains no NPC mutation, inventory policy or navigation decisions, so later actions can
 * reuse the block classification and vertical-plan queries without importing the demo service.
 */
internal fun NpcBlockObservation.isLumberjackWoodLog(): Boolean {
    val path = blockId.substringAfter(':', blockId)
    return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae")
}

internal fun NpcBlockObservation.isLumberjackLeafBlock(): Boolean =
    blockId.substringAfter(':', blockId).endsWith("_leaves")

internal fun NpcBlockObservation.isLumberjackSnowLayer(): Boolean = blockId == "minecraft:snow"

internal fun NpcBlockObservation.isLumberjackWorkBlock(): Boolean =
    isLumberjackWoodLog() || isLumberjackLeafBlock() || isLumberjackSnowLayer()

internal fun NpcWorldView.isLowestLumberjackWoodLog(position: NpcBlockPosition): Boolean {
    val below = observeBlock(NpcBlockPosition(position.x, position.y - 1, position.z))
    return below?.isLumberjackWoodLog() != true
}

/** The first contiguous trunk block at eye level, falling back to the highest lower one. */
internal fun NpcWorldView.initialLumberjackTrunkLog(trunkBase: NpcBlockPosition, eyeBlockY: Int): NpcBlockPosition? {
    var highestBelowEye: NpcBlockPosition? = null
    for (offset in 0..MAX_TRUNK_HEIGHT) {
        val candidate = NpcBlockPosition(trunkBase.x, trunkBase.y + offset, trunkBase.z)
        if (observeBlock(candidate)?.isLumberjackWoodLog() != true) {
            break
        }
        if (candidate.y >= eyeBlockY) {
            return candidate
        }
        highestBelowEye = candidate
    }
    return highestBelowEye
}

/** The first surviving log above the retained base which can become a player-like step. */
internal fun NpcWorldView.lowestRemainingLumberjackStep(
    trunkBase: NpcBlockPosition?,
    upperTarget: NpcBlockPosition,
): NpcBlockPosition? {
    val base = trunkBase ?: return null
    if (upperTarget.y <= base.y + 1) {
        return null
    }
    return (base.y + 1 until upperTarget.y)
        .asSequence()
        .map { y -> NpcBlockPosition(base.x, y, base.z) }
        .firstOrNull { candidate -> observeBlock(candidate)?.isLumberjackWoodLog() == true }
}

internal fun highestRemainingLumberjackTrunkLog(
    world: NpcWorldView,
    trunkBase: NpcBlockPosition,
    temporarySupports: Collection<NpcBlockPosition> = emptyList(),
): NpcBlockPosition? =
    (trunkBase.y..trunkBase.y + MAX_TRUNK_HEIGHT)
        .asSequence()
        .map { y -> NpcBlockPosition(trunkBase.x, y, trunkBase.z) }
        .filter { candidate -> candidate !in temporarySupports && world.observeBlock(candidate)?.isLumberjackWoodLog() == true }
        .maxByOrNull { it.y }

private const val MAX_TRUNK_HEIGHT = 32
