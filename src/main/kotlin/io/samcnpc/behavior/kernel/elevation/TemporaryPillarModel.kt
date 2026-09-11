package io.samcnpc.behavior.kernel.elevation

import io.samcnpc.core.api.NpcBlockPosition
import java.util.UUID

/** Persisted only while a kernel-created, player-like temporary scaffold exists in the world. */
internal data class TemporaryPillarSession(
    val taskId: UUID,
    var targetPosition: NpcBlockPosition,
    var state: TemporaryPillarState,
    val originalSelectedHotbarSlot: Int,
    var materialOriginalSlot: Int,
    var materialActiveSlot: Int,
    var materialItemId: String,
    val estimatedLevels: Int,
    var retries: Int,
    var lastResult: TemporaryPillarResultCode,
    var currentPlacement: NpcBlockPosition?,
    /** Exact selected-stack count expected after the placement now awaiting world verification. */
    var expectedMaterialCountAfterPlacement: Int?,
    val placedPositions: MutableList<NpcBlockPosition>,
    var positioningTicks: Int = 0,
    var cleanupTicks: Int = 0,
    val placedBlockIds: MutableMap<NpcBlockPosition, String> = LegacyPillarBlocks.expectedIds(placedPositions, materialItemId),
    /** Rebuilt runtime gate; never persist a claim that a prior world's geometry is still valid. */
    var revalidateSupports: Boolean = true,
)

internal enum class TemporaryPillarState {
    VALIDATE_BASE,
    POSITION_ON_SUPPORT,
    EQUIP_BLOCK,
    START_JUMP,
    WAIT_FOR_LEGAL_PLACEMENT_WINDOW,
    VERIFY_PLACEMENT,
    WAIT_FOR_LANDING,
    RESTORE_TASK_ITEM,
    DESCEND_BREAK,
    DESCEND_LAND,
}

/** Stable kernel diagnostics, intentionally independent of any caller's task vocabulary. */
internal enum class TemporaryPillarResultCode {
    PILLAR_NOT_NEEDED,
    PILLAR_STARTED,
    PILLAR_LEVEL_COMPLETED,
    PILLAR_TARGET_REACHED,
    PILLAR_NO_MATERIAL,
    PILLAR_INSUFFICIENT_MATERIAL,
    PILLAR_NO_HEADROOM,
    PILLAR_NO_SAFE_BASE,
    PILLAR_UNSAFE_ENVIRONMENT,
    PILLAR_DESTINATION_OCCUPIED,
    PILLAR_PLACEMENT_DENIED,
    PILLAR_PLACEMENT_FAILED,
    PILLAR_PLACEMENT_DESYNC,
    PILLAR_SUPPORT_LOST,
    PILLAR_SUPPORT_CHANGED,
    PILLAR_FELL,
    PILLAR_INTERRUPTED,
    PILLAR_TARGET_GONE,
    PILLAR_TARGET_CHANGED,
    PILLAR_TARGET_OBSTRUCTED,
    PILLAR_HEIGHT_LIMIT,
    PILLAR_WORLD_LIMIT,
    PILLAR_RECOVERY_REQUIRED,
    PILLAR_CLEANUP_COMPLETE,
    PILLAR_CLEANUP_INCOMPLETE,
}
