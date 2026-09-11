package io.samcnpc.behavior.task

import com.mojang.logging.LogUtils
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcWorldView
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID
import kotlin.math.abs

/** A report of a past effect, never permission to break or replace the current block. */
internal data class UnresolvedWorkBlock(
    val dimensionId: String,
    val position: NpcBlockPosition,
    val blockId: String?,
    val reason: String,
) {
    fun validationProblem(): String? = when {
        !validId(dimensionId) || (blockId != null && !validId(blockId)) -> "invalid block/dimension identity"
        abs(position.x.toLong()) > 29_999_984 || abs(position.z.toLong()) > 29_999_984 || abs(position.y.toLong()) > 2048 -> "invalid work position"
        reason.length !in 1..128 || reason.any { it.isISOControl() } -> "invalid work reason"
        else -> null
    }
    companion object {
        private val ID = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
        private fun validId(value: String) = value.length <= 256 && ID.matches(value)
    }
}

/** Bounded residue survives termination/reassignment. Unknown files are retained verbatim. */
internal class UnresolvedWorkStore private constructor() : SavedData() {
    private val records = mutableMapOf<UUID, List<UnresolvedWorkBlock>>()
    private var preservedData: CompoundTag? = null
    var problem: String? = null
        private set

    fun blocksFor(npcUuid: UUID): List<UnresolvedWorkBlock> = records[npcUuid] ?: emptyList()

    fun record(npcUuid: UUID, blocks: List<UnresolvedWorkBlock>): NpcActionResult {
        if (blocks.isEmpty()) return NpcActionResult.succeeded("no unresolved work")
        val unavailable = problem
        if (unavailable != null) return NpcActionResult.rejected(unavailable, NpcActionCode.NOT_READY)
        if (blocks.size > MAX_BLOCKS || blocks.any { it.validationProblem() != null }) {
            return NpcActionResult.rejected("invalid unresolved work report", NpcActionCode.INVALID_REQUEST)
        }
        // A position has one latest report; repeating cancellation cannot append duplicates.
        val combined = blocksFor(npcUuid).associateByTo(linkedMapOf()) { it.dimensionId to it.position }
        for (block in blocks) combined[block.dimensionId to block.position] = block
        if (combined.size > MAX_BLOCKS || (npcUuid !in records && records.size >= MAX_NPCS)) {
            return NpcActionResult.rejected("unresolved work storage full; active job and supports retained", NpcActionCode.NOT_READY)
        }
        records[npcUuid] = java.util.List.copyOf(combined.values)
        setDirty()
        return NpcActionResult.succeeded("unresolved work recorded")
    }

    /** Only a loaded, observed air block resolves a residue automatically. Replacements remain reported. */
    fun reconcileAbsent(npcUuid: UUID, dimensionId: String, world: NpcWorldView) {
        if (problem != null || world.dimensionId != dimensionId) return
        val previous = records[npcUuid] ?: return
        val remaining = previous.filterNot { it.dimensionId == dimensionId && world.observeBlock(it.position)?.isAir == true }
        if (previous.size == remaining.size) return
        if (remaining.isEmpty()) records.remove(npcUuid) else records[npcUuid] = java.util.List.copyOf(remaining)
        setDirty()
    }

    fun describe(npcUuid: UUID): String? {
        problem?.let { return it }
        val blocks = blocksFor(npcUuid)
        if (blocks.isEmpty()) return null
        return "unresolvedSupports=${blocks.size}; " + blocks.joinToString(prefix = "[", postfix = "]") {
            "${it.dimensionId}@${it.position.x},${it.position.y},${it.position.z} expected=${it.blockId ?: "unverified"} reason=${it.reason}"
        }
    }

    override fun save(tag: CompoundTag): CompoundTag {
        preservedData?.let { return tag.merge(it.copy()) }
        tag.putInt("version", 1)
        val entries = ListTag()
        for ((npcUuid, blocks) in records.toSortedMap(compareBy(UUID::toString))) {
            val entry = CompoundTag()
            entry.putUUID("npcUuid", npcUuid)
            val savedBlocks = ListTag()
            for (block in blocks) {
                val saved = CompoundTag()
                saved.putString("dimension", block.dimensionId)
                saved.putInt("x", block.position.x); saved.putInt("y", block.position.y); saved.putInt("z", block.position.z)
                block.blockId?.let { saved.putString("blockId", it) }
                saved.putString("reason", block.reason)
                savedBlocks.add(saved)
            }
            entry.put("blocks", savedBlocks)
            entries.add(entry)
        }
        tag.put("npcs", entries)
        return tag
    }

    companion object {
        internal const val DATA_NAME = "samcnpc_behavior_unresolved_work"
        private const val MAX_NPCS = 128
        private const val MAX_BLOCKS = 64
        private val LOGGER = LogUtils.getLogger()

        fun forServer(server: MinecraftServer): UnresolvedWorkStore {
            check(server.isSameThread) { "work reports require the authoritative server thread" }
            return server.overworld().dataStorage.computeIfAbsent(::load, ::UnresolvedWorkStore, DATA_NAME)
        }

        internal fun load(tag: CompoundTag): UnresolvedWorkStore {
            val store = UnresolvedWorkStore()
            try {
                require(tag.contains("version", Tag.TAG_INT.toInt()) && tag.getInt("version") == 1) { "unsupported version" }
                val entries = tag.get("npcs") as? ListTag
                require(entries != null && entries.size <= MAX_NPCS && (entries.isEmpty() || entries.elementType == Tag.TAG_COMPOUND)) { "invalid NPC records" }
                for (element in entries) {
                    val entry = element as CompoundTag
                    require(entry.hasUUID("npcUuid")) { "missing NPC UUID" }
                    val npcUuid = entry.getUUID("npcUuid")
                    require(npcUuid !in store.records) { "duplicate NPC report" }
                    val savedBlocks = entry.get("blocks") as? ListTag
                    require(savedBlocks != null && savedBlocks.size in 1..MAX_BLOCKS && savedBlocks.elementType == Tag.TAG_COMPOUND) { "invalid block reports" }
                    val seen = hashSetOf<Pair<String, NpcBlockPosition>>()
                    val blocks = savedBlocks.map { saved ->
                        val block = saved as CompoundTag
                        require(listOf("x", "y", "z").all { block.contains(it, Tag.TAG_INT.toInt()) }) { "invalid position type" }
                        require(block.contains("dimension", Tag.TAG_STRING.toInt()) && block.contains("reason", Tag.TAG_STRING.toInt())) { "missing identity/reason" }
                        require(!block.contains("blockId") || block.contains("blockId", Tag.TAG_STRING.toInt())) { "invalid block identity type" }
                        val value = UnresolvedWorkBlock(block.getString("dimension"), NpcBlockPosition(block.getInt("x"), block.getInt("y"), block.getInt("z")),
                            if (block.contains("blockId")) block.getString("blockId") else null, block.getString("reason"))
                        require(value.validationProblem() == null) { value.validationProblem() ?: "invalid report" }
                        require(seen.add(value.dimensionId to value.position)) { "duplicate block report" }
                        value
                    }
                    store.records[npcUuid] = java.util.List.copyOf(blocks)
                }
            } catch (error: IllegalArgumentException) {
                store.records.clear()
                store.preservedData = tag.copy()
                store.problem = "$DATA_NAME: ${error.message}; original report preserved, repair saved data before discarding any job with supports"
                LOGGER.warn(store.problem)
            }
            return store
        }
    }
}
