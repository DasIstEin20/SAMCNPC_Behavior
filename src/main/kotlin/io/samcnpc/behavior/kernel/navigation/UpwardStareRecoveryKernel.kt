package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcWorldView
import java.util.UUID
import kotlin.math.floor

/**
 * A bounded escape hatch for the visible "staring at the sky" failure mode. It is deliberately
 * transient: after a server restart the normal durable lumberjack job resumes without replaying
 * stale path intent.
 */
internal class UpwardStareRecoveryKernel<P> {
    private val stareWatchdogs: MutableMap<UUID, UpwardStareWatchdog> = mutableMapOf()
    private val activeDetours: MutableMap<UUID, WakeDetour<P>> = mutableMapOf()
    private val nextDirectionIndex: MutableMap<UUID, Int> = mutableMapOf()

    fun tick(npc: NpcFacade, world: NpcWorldView, monitor: Boolean, resumeState: P): Step<P>? {
        val npcUuid = npc.npcUuid
        val detour = activeDetours[npcUuid]
        if (detour != null) {
            return advanceDetour(npc, detour)
        }
        if (!monitor) {
            stareWatchdogs.remove(npcUuid)
            return null
        }
        val snapshot = npc.snapshot()
        val watchdog = stareWatchdogs.getOrPut(npcUuid) { UpwardStareWatchdog() }
        if (!watchdog.record(snapshot.position, snapshot.pitch)) {
            return null
        }
        stareWatchdogs.remove(npcUuid)
        return beginDetour(npc, world, resumeState)
    }

    fun clear(npcUuid: UUID) {
        stareWatchdogs.remove(npcUuid)
        activeDetours.remove(npcUuid)
        nextDirectionIndex.remove(npcUuid)
    }

    fun status(npcUuid: UUID): String {
        val detour = activeDetours[npcUuid]
        if (detour != null) {
            return "detour=${detour.direction.label}; ticks=${detour.ticks}/$MAX_DETOUR_TICKS; target=${detour.destination.x},${detour.destination.y},${detour.destination.z}"
        }
        val stareTicks = stareWatchdogs[npcUuid]?.ticks ?: 0
        return "$stareTicks/$UPWARD_STARE_TIMEOUT_TICKS"
    }

    private fun beginDetour(npc: NpcFacade, world: NpcWorldView, resumeState: P): Step<P> {
        val snapshot = npc.snapshot()
        val abortResult = if (snapshot.blockBreak != null) npc.abortBlockBreak() else null
        if (abortResult != null && abortResult.status in FAILURE_STATUSES) {
            return Step.Failed(abortResult)
        }
        npc.stopControl()
        val origin = NpcBlockPosition(
            floor(snapshot.position.x).toInt(),
            floor(snapshot.position.y).toInt(),
            floor(snapshot.position.z).toInt(),
        )
        val candidate = selectDetourLanding(npc.npcUuid, origin, world)
            ?: return Step.Unavailable(
                NpcActionResult.running("looked upward in one 4x4 horizontal cell for 10 seconds, but all four five-block wake destinations are unsafe"),
                abortResult != null,
            )
        val detour = WakeDetour(
            destination = candidate.position,
            direction = candidate.direction,
            resumeState = resumeState,
        )
        activeDetours[npc.npcUuid] = detour
        return advanceDetour(npc, detour, abortResult != null, started = true)
    }

    private fun advanceDetour(
        npc: NpcFacade,
        detour: WakeDetour<P>,
        abortedBlockBreak: Boolean = false,
        started: Boolean = false,
    ): Step<P> {
        val snapshot = npc.snapshot()
        if (horizontalDistanceSquared(snapshot.position, detour.destination) <= DETOUR_ARRIVAL_DISTANCE_SQR) {
            npc.stopControl()
            activeDetours.remove(npc.npcUuid)
            return Step.Resume(
                detour.resumeState,
                NpcActionResult.running("woke from an upward stare and completed the five-block ${detour.direction.label} detour"),
            )
        }
        detour.ticks += 1
        if (detour.ticks > MAX_DETOUR_TICKS) {
            npc.stopControl()
            activeDetours.remove(npc.npcUuid)
            return Step.Resume(
                detour.resumeState,
                NpcActionResult.running("the bounded five-block wake detour timed out; resuming the selected work route"),
            )
        }
        val navigation = npc.navigateTo(detour.destination, NAVIGATION_SPEED_MULTIPLIER)
        if (navigation.status in FAILURE_STATUSES) {
            activeDetours.remove(npc.npcUuid)
            return Step.Failed(navigation)
        }
        return Step.Detouring(
            NpcActionResult.running("looked upward in one 4x4 horizontal cell for 10 seconds; walking five blocks ${detour.direction.label} before resuming work"),
            abortedBlockBreak,
            started,
        )
    }

    private fun selectDetourLanding(npcUuid: UUID, origin: NpcBlockPosition, world: NpcWorldView): DetourCandidate? {
        val startIndex = nextDirectionIndex[npcUuid] ?: 0
        for (offset in EscapeDirection.entries.indices) {
            val index = (startIndex + offset) % EscapeDirection.entries.size
            val direction = EscapeDirection.entries[index]
            val position = NpcBlockPosition(
                origin.x + direction.deltaX * DETOUR_DISTANCE_BLOCKS,
                origin.y,
                origin.z + direction.deltaZ * DETOUR_DISTANCE_BLOCKS,
            )
            if (!world.isClearPlayerStandingCell(position)) {
                continue
            }
            nextDirectionIndex[npcUuid] = (index + 1) % EscapeDirection.entries.size
            return DetourCandidate(direction, NpcPosition(position.x + 0.5, position.y.toDouble(), position.z + 0.5))
        }
        nextDirectionIndex[npcUuid] = (startIndex + 1) % EscapeDirection.entries.size
        return null
    }

    private fun horizontalDistanceSquared(first: NpcPosition, second: NpcPosition): Double {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    internal sealed interface Step<out P> {
        data class Detouring(
            val result: NpcActionResult,
            val abortedBlockBreak: Boolean,
            /** True only on the tick that the bounded detour acquired a safe destination. */
            val started: Boolean,
        ) : Step<Nothing>
        data class Resume<P>(val state: P, val result: NpcActionResult) : Step<P>
        data class Unavailable(val result: NpcActionResult, val abortedBlockBreak: Boolean) : Step<Nothing>
        data class Failed(val result: NpcActionResult) : Step<Nothing>
    }

    private data class WakeDetour<P>(
        val destination: NpcPosition,
        val direction: EscapeDirection,
        val resumeState: P,
        var ticks: Int = 0,
    )

    private data class DetourCandidate(val direction: EscapeDirection, val position: NpcPosition)

    private enum class EscapeDirection(val deltaX: Int, val deltaZ: Int, val label: String) {
        NORTH(0, -1, "north"),
        EAST(1, 0, "east"),
        SOUTH(0, 1, "south"),
        WEST(-1, 0, "west"),
    }

    private companion object {
        const val UPWARD_STARE_TIMEOUT_TICKS = 200
        const val DETOUR_DISTANCE_BLOCKS = 5
        const val DETOUR_ARRIVAL_DISTANCE_SQR = 0.75 * 0.75
        const val MAX_DETOUR_TICKS = 120
        const val NAVIGATION_SPEED_MULTIPLIER = 1.3F
        val FAILURE_STATUSES = setOf(
            NpcActionStatus.REJECTED,
            NpcActionStatus.FAILED,
            NpcActionStatus.UNSUPPORTED,
        )
    }
}

/** Counts only horizontal 4x4 cells; a vertical hop can never reset this recovery timer. */
internal class UpwardStareWatchdog(
    private val timeoutTicks: Int = 200,
    private val upwardPitchThreshold: Float = -20.0F,
) {
    private var horizontalCell: HorizontalCell? = null
    var ticks: Int = 0
        private set

    fun record(position: NpcPosition, pitch: Float): Boolean {
        if (pitch > upwardPitchThreshold) {
            reset()
            return false
        }
        val currentCell = HorizontalCell(
            floor(position.x / HORIZONTAL_CELL_SIZE).toInt(),
            floor(position.z / HORIZONTAL_CELL_SIZE).toInt(),
        )
        if (currentCell != horizontalCell) {
            horizontalCell = currentCell
            ticks = 1
            return false
        }
        ticks += 1
        return ticks >= timeoutTicks
    }

    fun reset() {
        horizontalCell = null
        ticks = 0
    }

    private data class HorizontalCell(val x: Int, val z: Int)

    private companion object {
        const val HORIZONTAL_CELL_SIZE = 4.0
    }
}
