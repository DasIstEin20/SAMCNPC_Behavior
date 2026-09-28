package io.samcnpc.behavior.mission

import io.samcnpc.behavior.registry.BoundedBehaviorJson
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

internal enum class MissionState { READY, RUNNING, PAUSED, REVIEW_REQUIRED, CANCELLED, COMPLETED }

internal class MissionRecord(
    val id: UUID, val npc: UUID, val summoner: UUID, val definition: MissionDefinition,
    val fingerprints: Map<String,String>, val previousPacks: List<String>,
    var stageId: String = definition.stages.first().id,
    var state: MissionState = MissionState.READY,
    var remaining: Int = definition.stages.first().timeoutTicks,
    var taskId: UUID? = null, var definitionRevision: Int = 0,
    val confirmed: MutableMap<String,UUID> = linkedMapOf(),
    var detail: String = "ready", var resumeState: MissionState = MissionState.READY,
) {
    val stage: MissionStage get() = definition.stages.single { it.id == stageId }
    var restored = false
    var lastTick = Long.MIN_VALUE
    val terminal get() = state == MissionState.COMPLETED || state == MissionState.CANCELLED
}

/** The whole unrecognized file is preserved. Never recover corrupt state by replaying a stage. */
internal class MissionStore private constructor() : SavedData() {
    private val records = linkedMapOf<UUID,MissionRecord>()
    private var preserved: CompoundTag? = null
    var problem: String? = null
        private set
    fun get(npc: UUID) = records[npc]
    fun put(record: MissionRecord) {
        check(problem == null && (record.npc in records || records.size < MAX_RECORDS))
        records[record.npc] = record; setDirty()
    }
    fun canAdd(npc: UUID) = problem == null && (npc in records || records.size < MAX_RECORDS)
    override fun save(tag: CompoundTag): CompoundTag {
        val original = preserved
        if (original != null) return tag.merge(original.copy())
        tag.putInt("version",1)
        val rows = ListTag()
        for (record in records.values) rows.add(write(record))
        tag.put("missions",rows)
        return tag
    }
    companion object {
        const val DATA_NAME = "samcnpc_behavior_missions"
        private const val MAX_RECORDS = 256
        fun forServer(server: MinecraftServer): MissionStore {
            check(server.isSameThread)
            return server.overworld().dataStorage.computeIfAbsent(::load,::MissionStore,DATA_NAME)
        }
        internal fun load(tag: CompoundTag): MissionStore {
            val store = MissionStore()
            try {
                require(tag.contains("version",Tag.TAG_INT.toInt()) && tag.getInt("version") == 1)
                val rows=tag.get("missions") as? ListTag
                require(rows != null && rows.size <= MAX_RECORDS && (rows.isEmpty() || rows.elementType == Tag.TAG_COMPOUND))
                for (row in rows) {
                    val record=read(row as CompoundTag)
                    require(store.records.put(record.npc,record) == null) { "duplicate NPC mission" }
                }
            } catch (error: IllegalArgumentException) {
                store.records.clear(); store.preserved=tag.copy()
                store.problem="Invalid mission store preserved; no mission may run: ${error.message}".take(512)
                com.mojang.logging.LogUtils.getLogger().warn(store.problem)
            } catch (error: com.google.gson.JsonParseException) {
                store.records.clear(); store.preserved=tag.copy()
                store.problem="Invalid saved mission JSON preserved; no mission may run: ${error.message}".take(512)
                com.mojang.logging.LogUtils.getLogger().warn(store.problem)
            } catch (error: java.io.IOException) {
                store.records.clear(); store.preserved=tag.copy()
                store.problem="Unreadable saved mission preserved: ${error.message}".take(512)
                com.mojang.logging.LogUtils.getLogger().warn(store.problem)
            }
            return store
        }
        internal fun write(r: MissionRecord): CompoundTag {
            val t=CompoundTag()
            t.putUUID("id",r.id); t.putUUID("npc",r.npc); t.putUUID("summoner",r.summoner)
            t.putByteArray("definition",r.definition.body.toByteArray(Charsets.UTF_8)); t.putString("hash",r.definition.hash)
            t.putString("stage",r.stageId); t.putString("state",r.state.name); t.putInt("remaining",r.remaining)
            t.putString("detail",r.detail.take(512)); t.putString("resume",r.resumeState.name)
            r.taskId?.let { t.putUUID("task",it) }; t.putInt("definitionRevision",r.definitionRevision)
            val hashes=CompoundTag(); for ((id,hash) in r.fingerprints) hashes.putString(id,hash); t.put("packs",hashes)
            val done=CompoundTag(); for ((stage,task) in r.confirmed) done.putUUID(stage,task); t.put("confirmed",done)
            val previous=ListTag(); for (pack in r.previousPacks) previous.add(StringTag.valueOf(pack)); t.put("previous",previous)
            return t
        }
        internal fun read(t: CompoundTag): MissionRecord {
            require(t.hasUUID("id") && t.hasUUID("npc") && t.hasUUID("summoner"))
            require(t.contains("definition",Tag.TAG_BYTE_ARRAY.toInt()))
            val bytes=t.getByteArray("definition"); require(bytes.size in 1..BoundedBehaviorJson.MAX_BYTES)
            val definition=MissionDocuments.read(BoundedBehaviorJson.read(bytes.inputStream()))
            require(definition.hash == t.getString("hash")) { "definition hash differs" }
            val stage=definition.stages.singleOrNull { it.id == t.getString("stage") }
            require(stage != null && t.contains("remaining",Tag.TAG_INT.toInt()) && t.getInt("remaining") in 0..stage.timeoutTicks)
            val hashes=t.getCompound("packs"); require(hashes.size() in 1..128)
            val fingerprints=hashes.allKeys.associateWith { hashes.getString(it) }
            require(fingerprints.all { (id,hash) -> io.samcnpc.behavior.api.ItemQuery.validItemId(id) && hash.matches(Regex("[a-f0-9]{64}")) })
            require((definition.stages.map { it.pack } + definition.guards).all { it in fingerprints })
            val previous=t.getList("previous",Tag.TAG_STRING.toInt()).map { it.asString }
            require(previous.size <= 8 && previous.distinct().size == previous.size && previous.all(io.samcnpc.behavior.api.ItemQuery::validItemId))
            val done=t.getCompound("confirmed"); require(done.size() <= MissionDocuments.MAX_STAGES)
            val confirmed=linkedMapOf<String,UUID>()
            for (id in done.allKeys) { require(done.hasUUID(id) && definition.stages.any { it.id == id }); confirmed[id]=done.getUUID(id) }
            val state=MissionState.valueOf(t.getString("state"))
            val resume=MissionState.valueOf(t.getString("resume")); require(resume in setOf(MissionState.READY,MissionState.RUNNING))
            val bound=if(t.hasUUID("task")) t.getUUID("task") else null
            require(t.contains("definitionRevision",Tag.TAG_INT.toInt()) && t.getInt("definitionRevision") >= 0)
            if (state == MissionState.RUNNING && stage.order != null) require(bound != null)
            val prefix=linkedSetOf<String>()
            var cursor=definition.stages.first()
            while (cursor.id != stage.id) {
                require(prefix.add(cursor.id))
                cursor=definition.stages.single { it.id == cursor.success }
            }
            val finalReceipt=stage.success == null && stage.id in confirmed
            require(confirmed.keys == prefix || finalReceipt && confirmed.keys == prefix+stage.id) { "confirmed stages are not the exact executed prefix" }
            if (state == MissionState.COMPLETED) require(finalReceipt && confirmed.size == definition.stages.size)
            if (state == MissionState.READY) require(bound == null && !finalReceipt)
            val operationReceipts=confirmed.filterKeys { id -> definition.stages.single { it.id == id }.order != null }.values
            require(operationReceipts.distinct().size == operationReceipts.size) { "one task cannot confirm several operations" }
            require(confirmed.filterKeys { id -> definition.stages.single { it.id == id }.order == null }.values.all { it == t.getUUID("id") })
            val record=MissionRecord(t.getUUID("id"),t.getUUID("npc"),t.getUUID("summoner"),definition,
                java.util.Map.copyOf(fingerprints),java.util.List.copyOf(previous),stage.id,state,t.getInt("remaining"),bound,
                t.getInt("definitionRevision"),confirmed,t.getString("detail").take(512),resume)
            record.restored=true
            return record
        }
    }
}
