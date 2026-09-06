package io.samcnpc.behavior.runtime

import io.samcnpc.core.api.NpcActionResult
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * Behavior configuration belongs to the behavior module, not the NPC entity. The UUID mapping
 * survives reloads without making Core aware of behavior-pack IDs or JSON semantics.
 */
class BehaviorAssignmentStore private constructor() : SavedData() {
    private val packsByNpc: MutableMap<UUID, List<String>> = mutableMapOf()

    fun packsFor(npcUuid: UUID): List<String> = packsByNpc[npcUuid].orEmpty()

    fun replace(npcUuid: UUID, packIds: List<String>): NpcActionResult {
        if (packIds.size > MAX_PACKS || packIds.any { !PACK_ID.matches(it) } || packIds.toSet().size != packIds.size) {
            return NpcActionResult.rejected("behavior pack assignments must be unique namespaced IDs (maximum $MAX_PACKS)")
        }
        if (packIds.isEmpty()) {
            packsByNpc.remove(npcUuid)
        } else {
            packsByNpc[npcUuid] = packIds.toList()
        }
        setDirty()
        return NpcActionResult.succeeded("assigned ${packIds.size} behavior pack(s)")
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val assignments = ListTag()
        packsByNpc.toSortedMap(compareBy(UUID::toString)).forEach { (npcUuid, packIds) ->
            val entry = CompoundTag()
            entry.putUUID(KEY_NPC_UUID, npcUuid)
            val packs = ListTag()
            packIds.forEach { packs.add(StringTag.valueOf(it)) }
            entry.put(KEY_PACKS, packs)
            assignments.add(entry)
        }
        tag.put(KEY_ASSIGNMENTS, assignments)
        return tag
    }

    companion object {
        private const val DATA_NAME = "samcnpc_behavior_assignments"
        private const val KEY_ASSIGNMENTS = "assignments"
        private const val KEY_NPC_UUID = "npcUuid"
        private const val KEY_PACKS = "packs"
        private const val MAX_PACKS = 8
        private val PACK_ID = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")

        fun forServer(server: MinecraftServer): BehaviorAssignmentStore =
            server.overworld().dataStorage.computeIfAbsent(::load, ::BehaviorAssignmentStore, DATA_NAME)

        private fun load(tag: CompoundTag): BehaviorAssignmentStore {
            val store = BehaviorAssignmentStore()
            val assignments = tag.getList(KEY_ASSIGNMENTS, CompoundTag.TAG_COMPOUND.toInt())
            for (entryIndex in 0 until assignments.size.coerceAtMost(MAX_SAVED_ASSIGNMENTS)) {
                val entry = assignments.getCompound(entryIndex)
                if (!entry.hasUUID(KEY_NPC_UUID)) {
                    continue
                }
                val packIds = entry.getList(KEY_PACKS, CompoundTag.TAG_STRING.toInt())
                    .take(MAX_PACKS)
                    .map { it.asString }
                if (packIds.isNotEmpty() && packIds.all(PACK_ID::matches) && packIds.toSet().size == packIds.size) {
                    store.packsByNpc[entry.getUUID(KEY_NPC_UUID)] = packIds
                }
            }
            return store
        }

        private const val MAX_SAVED_ASSIGNMENTS = 4096
    }
}
