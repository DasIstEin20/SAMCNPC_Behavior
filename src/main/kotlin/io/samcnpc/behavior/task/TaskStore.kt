package io.samcnpc.behavior.task

import com.mojang.logging.LogUtils
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/** One bounded primary record/latest report per NPC, scoped to the authoritative server save. */
internal class TaskStore private constructor() : SavedData() {
    private val records = mutableMapOf<UUID, TaskRecord>()
    private val rejectedEntries = mutableListOf<CompoundTag>()
    private val problems = mutableMapOf<UUID, String>()
    private var fileProblem: String? = null
    private var preservedData: CompoundTag? = null

    fun get(npcUuid: UUID): TaskRecord? = records[npcUuid]
    fun problemFor(npcUuid: UUID): String? = fileProblem ?: problems[npcUuid]
    fun changed() { setDirty() }

    fun put(record: TaskRecord): NpcActionResult {
        val stateProblem = record.stateProblem()
        if (stateProblem != null) return NpcActionResult.rejected(stateProblem, NpcActionCode.NOT_READY)
        val problem = problemFor(record.npcUuid)
        if (problem != null) return NpcActionResult.rejected(problem, NpcActionCode.NOT_READY)
        val previous = records[record.npcUuid]
        if (previous != null && !previous.status.terminal) return NpcActionResult.rejected("NPC already has an active or paused task", NpcActionCode.CONFLICT)
        if (previous?.primary?.lumberjack?.residuePreserved == false) return NpcActionResult.rejected("previous wood task still retains unarchived work obligations", NpcActionCode.NOT_READY)
        if (previous == null && records.size + rejectedEntries.size >= MAX_RECORDS) return NpcActionResult.rejected("task storage is full", NpcActionCode.NOT_READY)
        records[record.npcUuid] = record
        setDirty()
        return NpcActionResult.succeeded("task ${record.id} assigned")
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val preserved = preservedData
        if (preserved != null) return tag.merge(preserved.copy())
        tag.putInt("version", VERSION)
        val list = ListTag()
        for (record in records.toSortedMap(compareBy(UUID::toString)).values) list.add(TaskCodec.write(record))
        for (entry in rejectedEntries) list.add(entry.copy())
        tag.put("tasks", list)
        return tag
    }

    companion object {
        private const val DATA_NAME = "samcnpc_behavior_tasks"
        private const val VERSION = 11
        private const val MAX_RECORDS = 4096
        private val LOGGER = LogUtils.getLogger()

        fun forServer(server: MinecraftServer): TaskStore {
            check(server.isSameThread) { "task persistence requires the authoritative server thread" }
            return server.overworld().dataStorage.computeIfAbsent(::load, ::TaskStore, DATA_NAME)
        }

        internal fun load(tag: CompoundTag): TaskStore {
            val store = TaskStore()
            val list = tag.get("tasks") as? ListTag
            if (!tag.contains("version", Tag.TAG_INT.toInt()) || tag.getInt("version") !in 1..VERSION ||
                list == null || list.size > MAX_RECORDS || (!list.isEmpty() && list.elementType != Tag.TAG_COMPOUND)) {
                store.fileProblem = "Cannot load $DATA_NAME: unsupported version or malformed tasks; original file preserved, safe idle active"
                store.preservedData = tag.copy()
                LOGGER.warn(store.fileProblem)
                return store
            }
            val seen = hashSetOf<UUID>()
            val firstEntries = mutableMapOf<UUID, CompoundTag>()
            for (element in list) {
                val entry = element as CompoundTag
                val npcUuid = if (entry.hasUUID("npcUuid")) entry.getUUID("npcUuid") else null
                try {
                    require(npcUuid != null) { "missing NPC UUID" }
                    require(seen.add(npcUuid)) { "duplicate task for NPC" }
                    val record = TaskCodec.read(entry, tag.getInt("version"))
                    require(tag.getInt("version") >= 11 || record.frames.none { it.definition is InventoryTaskDefinition && it.definition.version >= 3 } &&
                        record.logistics.policy.preparation == null && (record.logistics.policy.unload?.minimumFreeSlots ?: 0) == 0 &&
                        record.logistics.outcomes.none { it.kind == InventoryWorkKind.ENSURE }) { "pre-v11 did not contain preparation/space work" }
                    require(tag.getInt("version") >= 2 || record.frames.all { it.definition is NavigateTaskDefinition }) { "v1 did not contain resource tasks" }
                    require(tag.getInt("version") >= 3 || record.frames.none { it.definition is LumberjackTaskDefinition }) { "pre-v3 did not contain lumberjack tasks" }
                    require(tag.getInt("version") >= 4 || (record.frames.none { it.definition is AttackTaskDefinition } &&
                        record.reaction.policy == TaskReactionPolicy() && record.reaction.cooldownRemaining == 0 && record.lastCombat == null)) { "pre-v4 did not contain combat tasks/reactions" }
                    require(tag.getInt("version") >= 5 || record.frames.none { it.definition is CombatMissionDefinition || it.definition is TransportTaskDefinition }) { "pre-v5 did not contain combat missions or transport" }
                    store.records[npcUuid] = record
                    firstEntries[npcUuid] = entry
                } catch (error: IllegalArgumentException) {
                    val problem = "Invalid saved task npc=$npcUuid: ${error.message}; safe idle, repair the saved definition"
                    if (npcUuid != null) {
                        store.problems[npcUuid] = problem
                        store.records.remove(npcUuid)
                        val previous = firstEntries.remove(npcUuid)
                        if (previous != null) store.rejectedEntries.add(previous.copy())
                    }
                    store.rejectedEntries.add(entry.copy())
                    LOGGER.warn(problem)
                }
            }
            if (tag.getInt("version") < VERSION && store.rejectedEntries.isNotEmpty()) {
                store.fileProblem = "Older task file needs repair before migration; original file preserved, safe idle"
                store.preservedData = tag.copy()
                store.records.clear()
            }
            return store
        }
    }
}
