package io.samcnpc.behavior.runtime

import com.mojang.logging.LogUtils
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/** Versioned Behavior-owned assignments. Unsupported files remain readable but cannot be overwritten. */
class BehaviorAssignmentStore private constructor() : SavedData() {
    private val packsByNpc = mutableMapOf<UUID, List<String>>()
    private val problemsByNpc = mutableMapOf<UUID, String>()
    private val rejectedEntries = mutableListOf<CompoundTag>()
    private var preservedData: CompoundTag? = null
    private var fileProblem: String? = null

    fun packsFor(npcUuid: UUID): List<String> = packsByNpc[npcUuid].orEmpty()
    fun problemFor(npcUuid: UUID): String? = fileProblem ?: problemsByNpc[npcUuid]

    fun replace(npcUuid: UUID, packIds: List<String>): NpcActionResult {
        val problem = fileProblem
        if (problem != null) return NpcActionResult.rejected(problem, NpcActionCode.NOT_READY)
        if (!validPackIds(packIds)) {
            return NpcActionResult.rejected("behavior pack assignments must be unique namespaced IDs (maximum $MAX_PACKS, ID length $MAX_ID_LENGTH)")
        }
        val replacedRejected = rejectedEntries.count { it.hasUUID(KEY_NPC_UUID) && it.getUUID(KEY_NPC_UUID) == npcUuid }
        if (packIds.isNotEmpty() && npcUuid !in packsByNpc && packsByNpc.size + rejectedEntries.size - replacedRejected >= MAX_SAVED_ASSIGNMENTS) {
            return NpcActionResult.rejected("behavior assignment storage is full ($MAX_SAVED_ASSIGNMENTS NPCs)", NpcActionCode.NOT_READY)
        }
        if (packIds.isEmpty()) packsByNpc.remove(npcUuid) else packsByNpc[npcUuid] = java.util.List.copyOf(packIds)
        problemsByNpc.remove(npcUuid)
        rejectedEntries.removeAll { it.hasUUID(KEY_NPC_UUID) && it.getUUID(KEY_NPC_UUID) == npcUuid }
        setDirty()
        return NpcActionResult.succeeded("assigned ${packIds.size} behavior pack(s)")
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val preserved = preservedData
        if (preserved != null) return tag.merge(preserved.copy())
        tag.putInt(KEY_VERSION, DATA_VERSION)
        val assignments = ListTag()
        for ((npcUuid, packIds) in packsByNpc.toSortedMap(compareBy(UUID::toString))) {
            val entry = CompoundTag()
            entry.putUUID(KEY_NPC_UUID, npcUuid)
            val packs = ListTag()
            for (id in packIds) packs.add(StringTag.valueOf(id))
            entry.put(KEY_PACKS, packs)
            assignments.add(entry)
        }
        for (entry in rejectedEntries) assignments.add(entry.copy())
        tag.put(KEY_ASSIGNMENTS, assignments)
        return tag
    }

    companion object {
        private const val DATA_NAME = "samcnpc_behavior_assignments"
        private const val DATA_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_ASSIGNMENTS = "assignments"
        private const val KEY_NPC_UUID = "npcUuid"
        private const val KEY_PACKS = "packs"
        private const val MAX_PACKS = 8
        private const val MAX_ID_LENGTH = 256
        private const val MAX_SAVED_ASSIGNMENTS = 4096
        private val PACK_ID = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
        private val LOGGER = LogUtils.getLogger()

        fun forServer(server: MinecraftServer): BehaviorAssignmentStore {
            check(server.isSameThread) { "behavior persistence requires the authoritative server thread" }
            return server.overworld().dataStorage.computeIfAbsent(::load, ::BehaviorAssignmentStore, DATA_NAME)
        }

        internal fun validPackIds(ids: List<String>): Boolean = ids.size <= MAX_PACKS &&
            ids.all { it.length <= MAX_ID_LENGTH && PACK_ID.matches(it) } && ids.toSet().size == ids.size

        internal fun load(tag: CompoundTag): BehaviorAssignmentStore {
            val store = BehaviorAssignmentStore()
            val version = tag.getInt(KEY_VERSION)
            val malformedVersion = tag.contains(KEY_VERSION) && !tag.contains(KEY_VERSION, Tag.TAG_INT.toInt())
            val assignments = tag.getList(KEY_ASSIGNMENTS, Tag.TAG_COMPOUND.toInt())
            val malformedList = !tag.contains(KEY_ASSIGNMENTS, Tag.TAG_LIST.toInt()) ||
                (tag.get(KEY_ASSIGNMENTS) as? ListTag)?.let { !it.isEmpty() && it.elementType != Tag.TAG_COMPOUND } == true
            if (malformedVersion || version !in 0..DATA_VERSION || malformedList || assignments.size > MAX_SAVED_ASSIGNMENTS) {
                store.fileProblem = "Cannot load $DATA_NAME: unsupported version $version or malformed/oversized assignments; file preserved, safe idle active"
                store.preservedData = tag.copy()
                LOGGER.warn(store.fileProblem)
                return store
            }
            val seen = hashSetOf<UUID>()
            val firstEntries = mutableMapOf<UUID, CompoundTag>()
            var malformedEntries = 0
            for (index in 0 until assignments.size) {
                val entry = assignments.getCompound(index)
                if (!entry.hasUUID(KEY_NPC_UUID)) {
                    store.rejectedEntries.add(entry.copy())
                    malformedEntries++
                    continue
                }
                val npcUuid = entry.getUUID(KEY_NPC_UUID)
                val list = entry.get(KEY_PACKS) as? ListTag
                val ids = if (list != null && list.size <= MAX_PACKS && (list.isEmpty() || list.elementType == Tag.TAG_STRING)) {
                    list.map { it.asString }
                } else null
                val duplicate = !seen.add(npcUuid)
                if (duplicate || ids == null || !validPackIds(ids)) {
                    val previous = firstEntries.remove(npcUuid)
                    if (previous != null) store.rejectedEntries.add(previous.copy())
                    store.rejectedEntries.add(entry.copy())
                    store.packsByNpc.remove(npcUuid)
                    store.problemsByNpc[npcUuid] = "Malformed or duplicate saved behavior assignment for $npcUuid; safe idle active; reassign valid packs"
                    malformedEntries++
                } else {
                    firstEntries[npcUuid] = entry
                    if (ids.isNotEmpty()) store.packsByNpc[npcUuid] = java.util.List.copyOf(ids)
                }
            }
            if (malformedEntries > 0) LOGGER.warn("Skipped {} malformed/duplicate entries in {}; inspect behavior diagnostics and reassign affected NPCs", malformedEntries, DATA_NAME)
            if (version < DATA_VERSION) store.setDirty()
            return store
        }
    }
}
