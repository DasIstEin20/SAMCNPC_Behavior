package io.samcnpc.behavior.task

import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import java.util.UUID

internal object LumberjackTaskCodec {
    fun definitionKeys(version: Int,hasReplant: Boolean = false): Set<String> = setOf("area", "wood", "container", "quantity", "tools") + (if (version >= 2) setOf("supplySources") else emptySet()) + if(hasReplant) setOf("replant") else emptySet()
    fun writeDefinition(definition: LumberjackTaskDefinition, tag: CompoundTag) {
        tag.put("area", area(definition.area))
        tag.put("container", position(definition.destination))
        tag.putInt("quantity", definition.quantity)
        tag.putString("tools", definition.tools.name)
        if (definition.version >= 2) tag.put("supplySources", LumberjackSupplyCodec.writeChoices(definition.supplySources))
        definition.replant?.let { tag.put("replant",TaskCodec.writeDefinition(it)) }
        tag.put("wood", ListTag().apply { definition.wood.selectors.sorted().forEach { add(StringTag.valueOf(it)) } })
    }
    fun readDefinition(tag: CompoundTag, dimension: String, budget: TaskBudget, version: Int): LumberjackTaskDefinition {
        val selectors = list(tag, "wood", Tag.TAG_STRING, 16).map { it.asString }
        require(selectors.distinct().size == selectors.size) { "duplicate wood selector" }
        val tools = WorkToolPolicy.entries.firstOrNull { it.name == tag.getString("tools") }
            ?: throw IllegalArgumentException("unsupported work tool policy")
        return LumberjackTaskDefinition(dimension, readArea(compound(tag, "area")), WoodSelection(selectors),
            readPosition(compound(tag, "container")), integer(tag, "quantity"), tools, budget, version,
            if (version >= 2) LumberjackSupplyCodec.readChoices(compound(tag, "supplySources")) else null,
            if(tag.contains("replant")) PlantingOrderCodec.readNested(compound(tag,"replant")) else null)
    }

    fun write(state: LumberjackTaskState): CompoundTag = CompoundTag().apply {
        put("work", LumberjackDemoStore.encodeCheckpoint(state.job))
        put("resources", ListTag().apply {
            for ((id, resource) in state.resources.entries.toSortedMap()) add(CompoundTag().apply {
                putString("item", id); putInt("initial", resource.initial); putInt("gathered", resource.gathered)
                putInt("supplied", resource.supplied); putInt("consumed", resource.consumed); putInt("delivered", resource.delivered)
                putInt("lost", resource.lost); putInt("retained", resource.retained)
            })
        })
        putBoolean("uncertain", state.resources.uncertain)
        putBoolean("residuePreserved", state.residuePreserved)
        put("containerCounts", counts(state.containerCounts))
        putInt("containerSize", state.containerSize)
        put("deliveries", TaskObjectiveCodec.deliveries(state.deliveries))
        put("pastAreas", ListTag().apply { state.pastAreas.forEach { add(area(it)) } })
        state.supplies?.let { put("supplies", LumberjackSupplyCodec.write(it)) }
        if (state.job.deferredWood.isNotEmpty()) put("deferredWood", DeferredWoodCodec.write(state.job.deferredWood))
        state.pendingBreak?.let { checkpoint -> put("pendingBreak", position(checkpoint.position).apply { putString("blockId", checkpoint.blockId) }) }
        if(state.removedWood.isNotEmpty()) put("removedWood",ListTag().apply { for(p in state.removedWood) add(position(p)) })
        state.replantDefinition?.let { put("replantDefinition",TaskCodec.writeDefinition(it)) }
        put("removed", ListTag().apply {
            state.observedRemovedBlocks.sortedWith(compareBy<NpcBlockPosition> { it.x }.thenBy { it.y }.thenBy { it.z }).forEach { add(position(it)) }
        })
    }

    fun read(tag: CompoundTag, npcUuid: UUID, definition: LumberjackTaskDefinition, sourceVersion: Int = 5): LumberjackTaskState {
        val job = LumberjackDemoStore.decodeCheckpoint(compound(tag, "work"), npcUuid)
        if (tag.contains("deferredWood")) {
            require(sourceVersion >= 5) { "pre-v5 contains future deferred work state" }
            job.deferredWood.addAll(DeferredWoodCodec.read(tag.get("deferredWood"), definition))
        }
        job.deferredWoodEnabled = true
        require(job.dimensionId == definition.dimensionId) { "work dimension differs from its parent definition" }
        val supplies = if (definition.version >= 2) LumberjackSupplyCodec.read(compound(tag, "supplies"), definition.supplySources)
            else { require(!tag.contains("supplies")) { "v1 work contains future supply state" }; null }
        require(job.scanCursor in 0..definition.area.columns) { "work scan cursor exceeds its area" }
        val resources = linkedMapOf<String, HarvestResource>()
        for (element in list(tag, "resources", Tag.TAG_COMPOUND, HarvestResources.MAX_KINDS)) {
            val entry = element as CompoundTag
            val id = entry.getString("item")
            require(id !in resources) { "duplicate resource counter" }
            resources[id] = HarvestResource(integer(entry, "initial"), integer(entry, "gathered"), integer(entry, "supplied"),
                integer(entry, "consumed"), integer(entry, "delivered"), integer(entry, "lost"), integer(entry, "retained"))
        }
        require(tag.contains("uncertain", Tag.TAG_BYTE.toInt())) { "missing resource certainty" }
        val ledger = HarvestResources.restore(resources, tag.getBoolean("uncertain"))
        val size = integer(tag, "containerSize")
        require(size in 1..64) { "invalid container size" }
        val pending = if (tag.contains("pendingBreak")) {
            val checkpoint = compound(tag, "pendingBreak")
            val blockId = checkpoint.getString("blockId")
            require(HarvestResources.validCounts(mapOf(blockId to 1))) { "invalid pending block identity" }
            WorkBlockCheckpoint(readPosition(checkpoint), blockId)
        } else null
        val pastAreas = if (tag.contains("pastAreas")) {
            require(sourceVersion >= 5) { "pre-v5 contains future work-area history" }
            list(tag, "pastAreas", Tag.TAG_COMPOUND, 8).map { readArea(it as CompoundTag) }
        } else emptyList()
        require(pastAreas.all { it.validationProblem() == null }) { "invalid past work-area bounds" }
        val removed = list(tag, "removed", Tag.TAG_COMPOUND, LumberjackTaskState.MAX_REMOVED_BLOCKS).map { readPosition(it as CompoundTag) }
        require(removed.distinct().size == removed.size && removed.all { definition.area.contains(it) || pastAreas.any { old -> old.contains(it) } }) { "invalid removed work positions" }
        require(pending == null || definition.area.contains(pending.position)) { "pending break is outside the work area" }
        require(tag.contains("residuePreserved", Tag.TAG_BYTE.toInt())) { "missing residue report state" }
        val deliveries = if (tag.contains("deliveries")) {
            require(sourceVersion >= 5) { "pre-v5 contains future recipient history" }
            TaskObjectiveCodec.readDeliveries(tag)
        } else {
            val counts = resources.filterValues { it.delivered > 0 }.mapValues { it.value.delivered }
            if (counts.isEmpty()) emptyMap() else mapOf(definition.destination to counts)
        }
        require((deliveries.values.flatMap { it.keys } + resources.keys).all { id ->
            deliveries.values.sumOf { (it[id] ?: 0).toLong() } == (resources[id]?.delivered ?: 0).toLong()
        }) { "wood recipient history differs from physical delivery ledger" }
        val state = LumberjackTaskState(job, ledger, readCounts(tag, "containerCounts"), size, pending, removed, tag.getBoolean("residuePreserved"), supplies, deliveries.toMutableMap(), pastAreas.toMutableList())
        if(tag.contains("removedWood")) {
            require(sourceVersion >= 5 && definition.replant != null) { "wood removal evidence lacks replant permission" }
            val wood=list(tag,"removedWood",Tag.TAG_COMPOUND,LumberjackTaskState.MAX_REMOVED_BLOCKS).map { readPosition(it as CompoundTag) }
            require(wood.distinct().size == wood.size && wood.all { it in state.observedRemovedBlocks }) { "invalid confirmed cut-base evidence" }
            state.removedWood.addAll(wood)
        }
        if(tag.contains("replantDefinition")) {
            val requested=requireNotNull(definition.replant) { "unexpected wood replant stage" }
            val actual=PlantingOrderCodec.readNested(compound(tag,"replantDefinition"))
            require(job.phase == io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase.DEPOSIT_WOOD && job.pillarSession?.placedPositions.orEmpty().isEmpty() && ledger.delivered(definition.wood) >= definition.quantity && state.pendingBreak == null && WoodReplant.matchesRequested(actual,requested,state.removedWood)) { "wood replant stage lacks finished delivery and authorized cut bases" }
            state.replantDefinition=actual
            // The durable post-delivery stage restores this transient executor handoff.
            job.executionFinished=true
        }
        require(job.chestPosition == LumberjackSupply.position(definition, state)) { "work container differs from its parent phase/supply permissions" }
        job.externalSuppliesAllowed = definition.version == 1 || definition.supplySources != null
        return state
    }

    private fun area(area: WorkArea): CompoundTag = box(area.bounds).apply {
        put("exclusions", ListTag().apply { area.exclusions.forEach { add(box(it)) } })
    }
    private fun readArea(tag: CompoundTag): WorkArea = WorkArea(readBox(tag),
        list(tag, "exclusions", Tag.TAG_COMPOUND, 16).map { readBox(it as CompoundTag) })
    private fun box(box: WorkBox): CompoundTag = CompoundTag().apply { put("min", position(box.min)); put("max", position(box.max)) }
    private fun readBox(tag: CompoundTag): WorkBox = WorkBox(readPosition(compound(tag, "min")), readPosition(compound(tag, "max")))
    internal fun position(value: NpcBlockPosition): CompoundTag = CompoundTag().apply { putInt("x", value.x); putInt("y", value.y); putInt("z", value.z) }
    internal fun readPosition(tag: CompoundTag) = NpcBlockPosition(integer(tag, "x"), integer(tag, "y"), integer(tag, "z"))
    internal fun counts(values: Map<String, Int>): ListTag = ListTag().apply {
        for ((id, amount) in values.toSortedMap()) add(CompoundTag().apply { putString("item", id); putInt("count", amount) })
    }
    internal fun readCounts(tag: CompoundTag, key: String): Map<String, Int> {
        val values = linkedMapOf<String, Int>()
        for (element in list(tag, key, Tag.TAG_COMPOUND, HarvestResources.MAX_KINDS)) {
            val entry = element as CompoundTag
            val id = entry.getString("item")
            require(id !in values) { "duplicate container count" }
            values[id] = integer(entry, "count")
        }
        require(HarvestResources.validCounts(values)) { "invalid container counts" }
        return java.util.Map.copyOf(values)
    }
    private fun integer(tag: CompoundTag, key: String): Int {
        require(tag.contains(key, Tag.TAG_INT.toInt())) { "missing work integer $key" }
        return tag.getInt(key)
    }
    private fun compound(tag: CompoundTag, key: String): CompoundTag {
        require(tag.contains(key, Tag.TAG_COMPOUND.toInt())) { "missing work compound $key" }
        return tag.getCompound(key)
    }
    private fun list(tag: CompoundTag, key: String, type: Byte, limit: Int): ListTag {
        val value = tag.get(key) as? ListTag ?: throw IllegalArgumentException("missing work list $key")
        require(value.size <= limit && (value.isEmpty() || value.elementType == type)) { "invalid/oversized work list $key" }
        return value
    }
}
