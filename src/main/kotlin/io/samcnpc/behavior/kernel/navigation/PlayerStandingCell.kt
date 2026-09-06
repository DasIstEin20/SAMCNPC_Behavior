package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcWorldView

/**
 * Shared, read-only landing checks for this Behavior-only demo. Core still validates every
 * navigation request; these checks merely keep policy from selecting an obviously unsafe cell.
 */
internal fun NpcWorldView.isClearPlayerStandingCell(position: NpcBlockPosition): Boolean {
    val feet = observeBlock(position) ?: return false
    val head = observeBlock(NpcBlockPosition(position.x, position.y + 1, position.z)) ?: return false
    val floor = observeBlock(NpcBlockPosition(position.x, position.y - 1, position.z)) ?: return false
    return !feet.isSolid && !head.isSolid &&
        !isLeafOrSupportedSnowObstacle(position) &&
        !isLeafOrSupportedSnowObstacle(NpcBlockPosition(position.x, position.y + 1, position.z)) &&
        !floor.isAir
}

internal fun NpcWorldView.isLeafOrSupportedSnowObstacle(position: NpcBlockPosition): Boolean {
    val observation = observeBlock(position) ?: return false
    if (observation.blockId.substringAfter(':', observation.blockId).endsWith("_leaves")) {
        return true
    }
    if (observation.blockId != "minecraft:snow") {
        return false
    }
    val below = observeBlock(NpcBlockPosition(position.x, position.y - 1, position.z)) ?: return false
    return below.blockId.substringAfter(':', below.blockId).endsWith("_leaves")
}
