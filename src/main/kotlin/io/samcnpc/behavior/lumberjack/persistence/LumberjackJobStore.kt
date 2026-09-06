package io.samcnpc.behavior.lumberjack.persistence

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarResultCode
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarSession
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarState
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.lumberjack.LumberjackCollectionBudget
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * Durable, bounded policy state for the disposable lumberjack demo. The NPC entity never knows
 * this state exists; losing the Behavior JAR leaves the Core body safely idle.
 */
internal class LumberjackDemoStore private constructor() : SavedData() {
    private val jobs: MutableMap<UUID, LumberjackDemoJob> = mutableMapOf()

    fun jobFor(npcUuid: UUID): LumberjackDemoJob? = jobs[npcUuid]

    fun put(job: LumberjackDemoJob) {
        jobs[job.npcUuid] = job
        setDirty()
    }

    fun remove(npcUuid: UUID): LumberjackDemoJob? = jobs.remove(npcUuid)?.also { setDirty() }

    fun markChanged() {
        setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt(KEY_VERSION, DATA_VERSION)
        val savedJobs = ListTag()
        jobs.toSortedMap(compareBy(UUID::toString)).forEach { (_, job) ->
            val entry = CompoundTag()
            entry.putUUID(KEY_NPC_UUID, job.npcUuid)
            entry.putString(KEY_DIMENSION, job.dimensionId)
            entry.put(KEY_CHEST, job.chestPosition.toTag())
            entry.put(KEY_WORK_CENTER, job.workCenter.toTag())
            entry.putString(KEY_PHASE, job.phase.name)
            entry.putInt(KEY_SCAN_CURSOR, job.scanCursor)
            entry.putInt(KEY_PICKUP_TICKS, job.pickupTicks)
            entry.putInt(KEY_PICKUP_QUIET_TICKS, job.pickupQuietTicks)
            entry.putInt(KEY_SCAFFOLD_RECOVERY_ATTEMPTS, job.scaffoldRecoveryAttempts)
            entry.putBoolean(KEY_RESUME_WORK_AFTER_DEPOSIT, job.resumeWorkAfterDeposit)
            job.targetPosition?.let { entry.put(KEY_TARGET, it.toTag()) }
            job.trunkBasePosition?.let { entry.put(KEY_TRUNK_BASE, it.toTag()) }
            job.blockedLogPosition?.let { entry.put(KEY_BLOCKED_LOG, it.toTag()) }
            job.accessReturnTarget?.let { entry.put(KEY_ACCESS_RETURN_TARGET, it.toTag()) }
            job.miningStance?.let { entry.put(KEY_MINING_STANCE, it.toTag()) }
            val rejectedStances = ListTag()
            job.rejectedMiningStances.take(MAX_REJECTED_STANCES).forEach { rejectedStances.add(it.toTag()) }
            entry.put(KEY_REJECTED_STANCES, rejectedStances)
            entry.putBoolean(KEY_INITIAL_TRUNK_TARGET_PENDING, job.initialTrunkTargetPending)
            entry.putInt(KEY_CLIMB_JUMP_ATTEMPTS, job.climbJumpAttempts)
            entry.putBoolean(KEY_SCAFFOLD_MATERIAL_RECOVERY, job.scaffoldMaterialRecovery)
            entry.putBoolean(KEY_SCAFFOLD_MATERIAL_RECOVERY_PENDING, job.scaffoldMaterialRecoveryPending)
            entry.putInt(KEY_FAILED_WORK_ATTEMPTS, job.failedWorkAttempts)
            entry.putBoolean(KEY_ABANDON_AFTER_PILLAR_CLEANUP, job.abandonTreeAfterPillarCleanup)
            job.pillarSession?.let { pillar -> entry.put(KEY_PILLAR_SESSION, pillar.toTag()) }
            val priorPacks = ListTag()
            job.previousPackIds.forEach { priorPacks.add(StringTag.valueOf(it)) }
            entry.put(KEY_PREVIOUS_PACKS, priorPacks)
            val initialWood = ListTag()
            job.initialWoodCounts.toSortedMap().forEach { (itemId, count) ->
                val woodEntry = CompoundTag()
                woodEntry.putString(KEY_ITEM_ID, itemId)
                woodEntry.putInt(KEY_COUNT, count)
                initialWood.add(woodEntry)
            }
            entry.put(KEY_INITIAL_WOOD, initialWood)
            savedJobs.add(entry)
        }
        tag.put(KEY_JOBS, savedJobs)
        return tag
    }

    companion object {
        private const val DATA_NAME = "samcnpc_behavior_lumberjack_demo"
        private const val DATA_VERSION = 16
        private const val KEY_VERSION = "version"
        private const val KEY_JOBS = "jobs"
        private const val KEY_NPC_UUID = "npcUuid"
        private const val KEY_DIMENSION = "dimension"
        private const val KEY_CHEST = "chest"
        private const val KEY_WORK_CENTER = "workCenter"
        private const val KEY_PHASE = "phase"
        private const val KEY_SCAN_CURSOR = "scanCursor"
        private const val KEY_PICKUP_TICKS = "pickupTicks"
        private const val KEY_PICKUP_QUIET_TICKS = "pickupQuietTicks"
        private const val KEY_SCAFFOLD_RECOVERY_ATTEMPTS = "scaffoldRecoveryAttempts"
        private const val KEY_RESUME_WORK_AFTER_DEPOSIT = "resumeWorkAfterDeposit"
        private const val KEY_TARGET = "target"
        private const val KEY_TRUNK_BASE = "trunkBase"
        private const val KEY_BLOCKED_LOG = "blockedLog"
        private const val KEY_ACCESS_RETURN_TARGET = "accessReturnTarget"
        private const val KEY_MINING_STANCE = "miningStance"
        private const val KEY_REJECTED_STANCES = "rejectedStances"
        private const val KEY_INITIAL_TRUNK_TARGET_PENDING = "initialTrunkTargetPending"
        private const val KEY_CLIMB_JUMP_ATTEMPTS = "climbJumpAttempts"
        private const val KEY_SCAFFOLD_MATERIAL_RECOVERY = "scaffoldMaterialRecovery"
        private const val KEY_SCAFFOLD_MATERIAL_RECOVERY_PENDING = "scaffoldMaterialRecoveryPending"
        private const val KEY_FAILED_WORK_ATTEMPTS = "failedWorkAttempts"
        private const val KEY_ABANDON_AFTER_PILLAR_CLEANUP = "abandonAfterPillarCleanup"
        private const val KEY_PILLAR_SESSION = "pillarSession"
        private const val KEY_PREVIOUS_PACKS = "previousPacks"
        private const val KEY_INITIAL_WOOD = "initialWood"
        private const val KEY_ITEM_ID = "itemId"
        private const val KEY_COUNT = "count"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_Z = "z"
        private const val MAX_SAVED_JOBS = 128
        private const val MAX_PREVIOUS_PACKS = 8
        private const val MAX_INITIAL_WOOD_KINDS = 32
        private const val MAX_REJECTED_STANCES = 4
        private const val MAX_CLIMB_JUMP_ATTEMPTS = 1
        private const val MAX_FAILED_WORK_ATTEMPTS = 3
        private val PACK_ID = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")

        fun forServer(server: MinecraftServer): LumberjackDemoStore =
            server.overworld().dataStorage.computeIfAbsent(::load, ::LumberjackDemoStore, DATA_NAME)

        internal fun load(tag: CompoundTag): LumberjackDemoStore {
            val store = LumberjackDemoStore()
            val savedVersion = tag.getInt(KEY_VERSION)
            if (savedVersion !in 0..DATA_VERSION) {
                return store
            }
            val entries = tag.getList(KEY_JOBS, Tag.TAG_COMPOUND.toInt())
            for (index in 0 until entries.size.coerceAtMost(MAX_SAVED_JOBS)) {
                val entry = entries.getCompound(index)
                if (!entry.hasUUID(KEY_NPC_UUID)) {
                    continue
                }
                val chest = entry.positionOrNull(KEY_CHEST) ?: continue
                val workCenter = entry.positionOrNull(KEY_WORK_CENTER) ?: continue
                val phase = LumberjackDemoPhase.entries.firstOrNull { it.name == entry.getString(KEY_PHASE) } ?: continue
                val previousPackIds = entry.getList(KEY_PREVIOUS_PACKS, Tag.TAG_STRING.toInt())
                    .take(MAX_PREVIOUS_PACKS)
                    .map { it.asString }
                    .filter(PACK_ID::matches)
                    .distinct()
                val initialWoodCounts = mutableMapOf<String, Int>()
                val initialWood = entry.getList(KEY_INITIAL_WOOD, Tag.TAG_COMPOUND.toInt())
                for (woodIndex in 0 until initialWood.size.coerceAtMost(MAX_INITIAL_WOOD_KINDS)) {
                    val woodEntry = initialWood.getCompound(woodIndex)
                    val itemId = woodEntry.getString(KEY_ITEM_ID)
                    val count = woodEntry.getInt(KEY_COUNT)
                    if (itemId.isNotBlank() && count > 0) {
                        initialWoodCounts[itemId] = count
                    }
                }
                val job = LumberjackDemoJob(
                    npcUuid = entry.getUUID(KEY_NPC_UUID),
                    dimensionId = entry.getString(KEY_DIMENSION),
                    chestPosition = chest,
                    workCenter = workCenter,
                    previousPackIds = previousPackIds,
                    phase = phase,
                    // Version 4 and older stored a block-by-block cursor. Version 5 introduced
                    // horizontal columns; later policy-only versions must not reinterpret an
                    // already-column-based in-flight cursor.
                    scanCursor = if (savedVersion < COLUMN_SCAN_DATA_VERSION) {
                        (entry.getInt(KEY_SCAN_CURSOR).coerceAtLeast(0) / LEGACY_SCAN_BLOCKS_PER_COLUMN)
                    } else {
                        entry.getInt(KEY_SCAN_CURSOR).coerceAtLeast(0)
                    },
                    pickupTicks = entry.getInt(KEY_PICKUP_TICKS).coerceIn(0, LumberjackCollectionBudget.MAX_TICKS),
                    pickupQuietTicks = entry.getInt(KEY_PICKUP_QUIET_TICKS).coerceIn(0, LumberjackCollectionBudget.QUIET_TICKS),
                    scaffoldRecoveryAttempts = entry.getInt(KEY_SCAFFOLD_RECOVERY_ATTEMPTS).coerceIn(0, 3),
                    resumeWorkAfterDeposit = entry.getBoolean(KEY_RESUME_WORK_AFTER_DEPOSIT),
                    targetPosition = entry.positionOrNull(KEY_TARGET),
                    trunkBasePosition = entry.positionOrNull(KEY_TRUNK_BASE),
                    blockedLogPosition = entry.positionOrNull(KEY_BLOCKED_LOG),
                    accessReturnTarget = entry.positionOrNull(KEY_ACCESS_RETURN_TARGET),
                    miningStance = entry.positionOrNull(KEY_MINING_STANCE),
                    rejectedMiningStances = entry.getList(KEY_REJECTED_STANCES, Tag.TAG_COMPOUND.toInt())
                        .take(MAX_REJECTED_STANCES)
                        .mapNotNull { (it as? CompoundTag)?.positionOrNullDirect() }
                        .distinct()
                        .toMutableList(),
                    initialTrunkTargetPending = entry.getBoolean(KEY_INITIAL_TRUNK_TARGET_PENDING),
                    climbJumpAttempts = entry.getInt(KEY_CLIMB_JUMP_ATTEMPTS).coerceIn(0, MAX_CLIMB_JUMP_ATTEMPTS),
                    // Forward commitment is intentionally transient. After a server restart,
                    // the next safe ground tick either observes a completed step or escalates
                    // to the bounded scaffold instead of replaying stale momentum.
                    climbForwardTicks = 0,
                    scaffoldMaterialRecovery = entry.getBoolean(KEY_SCAFFOLD_MATERIAL_RECOVERY),
                    scaffoldMaterialRecoveryPending = entry.getBoolean(KEY_SCAFFOLD_MATERIAL_RECOVERY_PENDING),
                    failedWorkAttempts = entry.getInt(KEY_FAILED_WORK_ATTEMPTS).coerceIn(0, MAX_FAILED_WORK_ATTEMPTS),
                    abandonTreeAfterPillarCleanup = entry.getBoolean(KEY_ABANDON_AFTER_PILLAR_CLEANUP),
                    initialWoodCounts = initialWoodCounts,
                    pillarSession = entry.pillarSessionOrNull(),
                )
                recoverLegacyPlacement(savedVersion, job)
                store.jobs[job.npcUuid] = job
            }
            return store
        }

        private fun recoverLegacyPlacement(version: Int, job: LumberjackDemoJob) {
            val pillar = job.pillarSession ?: return
            if (version >= 16 || pillar.state != TemporaryPillarState.VERIFY_PLACEMENT) return
            // Old VERIFY means Core already accepted placement, but the old counter receipt
            // crossed a tick/save boundary. Retain that last support and descend conservatively;
            // never compare a current inventory (possibly after pickup) with a historical count.
            val pending = pillar.currentPlacement
            if (pending != null && pending !in pillar.placedPositions && pillar.placedPositions.size < MAX_PILLAR_POSITIONS) {
                pillar.placedPositions.add(pending)
            }
            pillar.currentPlacement = null
            pillar.expectedMaterialCountAfterPlacement = null
            pillar.state = TemporaryPillarState.DESCEND_BREAK
            job.phase = LumberjackDemoPhase.PILLAR_CLEANUP
            job.abandonTreeAfterPillarCleanup = false
        }

        private const val LEGACY_SCAN_BLOCKS_PER_COLUMN = 27
        private const val COLUMN_SCAN_DATA_VERSION = 5

        private fun NpcBlockPosition.toTag(): CompoundTag = CompoundTag().also { tag ->
            tag.putInt(KEY_X, x)
            tag.putInt(KEY_Y, y)
            tag.putInt(KEY_Z, z)
        }

        private fun CompoundTag.positionOrNull(key: String): NpcBlockPosition? {
            if (!contains(key, Tag.TAG_COMPOUND.toInt())) {
                return null
            }
            val position = getCompound(key)
            return NpcBlockPosition(position.getInt(KEY_X), position.getInt(KEY_Y), position.getInt(KEY_Z))
        }

        private fun TemporaryPillarSession.toTag(): CompoundTag = CompoundTag().also { tag ->
            tag.putUUID(KEY_TASK_ID, taskId)
            tag.put(KEY_PILLAR_TARGET, targetPosition.toTag())
            tag.putString(KEY_PILLAR_STATE, state.name)
            tag.putInt(KEY_PILLAR_ORIGINAL_SLOT, originalSelectedHotbarSlot)
            tag.putInt(KEY_PILLAR_SOURCE_SLOT, materialOriginalSlot)
            tag.putInt(KEY_PILLAR_ACTIVE_SLOT, materialActiveSlot)
            tag.putString(KEY_PILLAR_ITEM_ID, materialItemId)
            tag.putInt(KEY_PILLAR_ESTIMATE, estimatedLevels)
            tag.putInt(KEY_PILLAR_RETRIES, retries)
            tag.putInt(KEY_PILLAR_POSITIONING_TICKS, positioningTicks)
            tag.putInt(KEY_PILLAR_CLEANUP_TICKS, cleanupTicks)
            tag.putString(KEY_PILLAR_LAST_RESULT, lastResult.name)
            currentPlacement?.let { tag.put(KEY_PILLAR_PLACEMENT, it.toTag()) }
            expectedMaterialCountAfterPlacement?.let { count -> tag.putInt(KEY_PILLAR_EXPECTED_COUNT, count) }
            val placed = ListTag()
            placedPositions.take(MAX_PILLAR_POSITIONS).forEach { placed.add(it.toTag()) }
            tag.put(KEY_PILLAR_POSITIONS, placed)
        }

        private fun CompoundTag.pillarSessionOrNull(): TemporaryPillarSession? {
            if (!contains(KEY_PILLAR_SESSION, Tag.TAG_COMPOUND.toInt())) {
                return null
            }
            val tag = getCompound(KEY_PILLAR_SESSION)
            if (!tag.hasUUID(KEY_TASK_ID)) {
                return null
            }
            val target = tag.positionOrNull(KEY_PILLAR_TARGET) ?: return null
            val state = TemporaryPillarState.entries.firstOrNull { it.name == tag.getString(KEY_PILLAR_STATE) } ?: return null
            val materialItemId = tag.getString(KEY_PILLAR_ITEM_ID).takeIf(String::isNotBlank) ?: return null
            val sourceSlot = tag.getInt(KEY_PILLAR_SOURCE_SLOT)
            val activeSlot = tag.getInt(KEY_PILLAR_ACTIVE_SLOT)
            val selectedSlot = tag.getInt(KEY_PILLAR_ORIGINAL_SLOT)
            if (sourceSlot !in 0 until INVENTORY_SIZE || activeSlot !in 0 until HOTBAR_SIZE || selectedSlot !in 0 until HOTBAR_SIZE) {
                return null
            }
            val placed = tag.getList(KEY_PILLAR_POSITIONS, Tag.TAG_COMPOUND.toInt())
                .take(MAX_PILLAR_POSITIONS)
                .mapNotNull { (it as? CompoundTag)?.positionOrNullDirect() }
                .distinct()
                .toMutableList()
            return TemporaryPillarSession(
                taskId = tag.getUUID(KEY_TASK_ID),
                targetPosition = target,
                state = state,
                originalSelectedHotbarSlot = selectedSlot,
                materialOriginalSlot = sourceSlot,
                materialActiveSlot = activeSlot,
                materialItemId = materialItemId,
                estimatedLevels = tag.getInt(KEY_PILLAR_ESTIMATE).coerceIn(1, MAX_PILLAR_POSITIONS),
                retries = tag.getInt(KEY_PILLAR_RETRIES).coerceIn(0, MAX_PILLAR_RETRIES),
                lastResult = TemporaryPillarResultCode.entries.firstOrNull { it.name == tag.getString(KEY_PILLAR_LAST_RESULT) }
                    ?: TemporaryPillarResultCode.PILLAR_STARTED,
                currentPlacement = tag.positionOrNull(KEY_PILLAR_PLACEMENT),
                expectedMaterialCountAfterPlacement = if (tag.contains(KEY_PILLAR_EXPECTED_COUNT, Tag.TAG_INT.toInt())) {
                    tag.getInt(KEY_PILLAR_EXPECTED_COUNT).coerceAtLeast(0)
                } else {
                    null
                },
                placedPositions = placed,
                positioningTicks = tag.getInt(KEY_PILLAR_POSITIONING_TICKS).coerceIn(0, 41),
                cleanupTicks = tag.getInt(KEY_PILLAR_CLEANUP_TICKS).coerceIn(0, 601),
            )
        }

        private fun CompoundTag.positionOrNullDirect(): NpcBlockPosition? =
            takeIf { contains(KEY_X, Tag.TAG_INT.toInt()) && contains(KEY_Y, Tag.TAG_INT.toInt()) && contains(KEY_Z, Tag.TAG_INT.toInt()) }
                ?.let { NpcBlockPosition(getInt(KEY_X), getInt(KEY_Y), getInt(KEY_Z)) }

        private const val KEY_TASK_ID = "taskId"
        private const val KEY_PILLAR_TARGET = "target"
        private const val KEY_PILLAR_STATE = "state"
        private const val KEY_PILLAR_ORIGINAL_SLOT = "originalSlot"
        private const val KEY_PILLAR_SOURCE_SLOT = "sourceSlot"
        private const val KEY_PILLAR_ACTIVE_SLOT = "activeSlot"
        private const val KEY_PILLAR_ITEM_ID = "materialItemId"
        private const val KEY_PILLAR_ESTIMATE = "estimatedLevels"
        private const val KEY_PILLAR_RETRIES = "retries"
        private const val KEY_PILLAR_POSITIONING_TICKS = "positioningTicks"
        private const val KEY_PILLAR_CLEANUP_TICKS = "cleanupTicks"
        private const val KEY_PILLAR_LAST_RESULT = "lastResult"
        private const val KEY_PILLAR_PLACEMENT = "placement"
        private const val KEY_PILLAR_EXPECTED_COUNT = "expectedMaterialCount"
        private const val KEY_PILLAR_POSITIONS = "placedPositions"
        private const val MAX_PILLAR_POSITIONS = 12
        private const val MAX_PILLAR_RETRIES = 2
        private const val INVENTORY_SIZE = 36
        private const val HOTBAR_SIZE = 9
    }
}
