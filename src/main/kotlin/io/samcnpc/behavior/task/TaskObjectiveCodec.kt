package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.*

/** Bounded factual reports; none of these rows grants permission to repeat a world action. */
internal object TaskObjectiveCodec {
    fun write(value: TaskObjectiveReport) = CompoundTag().apply {
        putUUID("objective", value.objectiveId); putInt("revision", value.revision)
        put("definition", TaskCodec.writeDefinition(value.definition)); putInt("confirmed", value.confirmed); putString("detail", value.detail)
        put("resources", resources(value.resources)); put("deliveries", deliveries(value.deliveries))
        value.mining?.let { put("mining", it.write()) }
        value.food?.let { put("food", it.write()) }
        value.planting?.let { put("planting",it.write()) }
        value.farming?.let { put("farming", it.write()) }
    }
    fun read(tag: CompoundTag): TaskObjectiveReport {
        require(tag.allKeys == setOf("objective", "revision", "definition", "confirmed", "detail", "resources", "deliveries") + listOf("mining","food","farming","planting").filter(tag::contains) && tag.hasUUID("objective")) { "invalid objective report" }
        val revision = TaskChangeCodec.integer(tag, "revision"); val confirmed = TaskChangeCodec.integer(tag, "confirmed")
        require(revision in 0..32 && confirmed in 0..HarvestResource.MAX_COUNT && tag.getString("detail").length <= 1024) { "oversized objective report" }
        val definition = TaskCodec.readDefinition(TaskChangeCodec.compound(tag, "definition"))
        val resources = readResources(tag); val deliveries = readDeliveries(tag)
        require((resources.keys + deliveries.values.flatMap { it.keys }).all { id ->
            deliveries.values.sumOf { (it[id] ?: 0).toLong() } == (resources[id]?.delivered ?: 0).toLong()
        }) { "recipient report differs from resource delivery facts" }
        val miningEvidence = if (definition is MiningTaskDefinition) MiningObjectiveEvidence.restore(TaskChangeCodec.compound(tag,"mining"),definition) else {
            require(!tag.contains("mining")) { "unexpected archived mining evidence" }; null
        }
        val mining = if (definition is MiningTaskDefinition) checkNotNull(miningEvidence).read(definition) else null
        require(mining == null || mining.resources.physical.entries == resources && mining.deliveries == deliveries) { "archived mining evidence differs from report" }
        val foodEvidence=if (definition is FoodTaskDefinition) FoodObjectiveEvidence.restore(TaskChangeCodec.compound(tag,"food"),definition) else {
            require(!tag.contains("food")) { "unexpected archived food evidence" }; null
        }
        val food=if (definition is FoodTaskDefinition) checkNotNull(foodEvidence).read(definition) else null
        require(food == null || food.resources.physical.entries == resources && food.deliveries == deliveries) { "archived food evidence differs from report" }
        val farmEvidence=if (definition is FarmTaskDefinition) FarmObjectiveEvidence.restore(TaskChangeCodec.compound(tag,"farming"),definition) else {
            require(!tag.contains("farming")) { "unexpected archived farm evidence" }; null
        }
        val farming=if (definition is FarmTaskDefinition) checkNotNull(farmEvidence).read(definition) else null
        require(farming == null || farming.resources.physical.entries == resources && farming.deliveries == deliveries) { "archived farm evidence differs from report" }
        val plantingEvidence=if(tag.contains("planting")) PlantingObjectiveEvidence.restore(TaskChangeCodec.compound(tag,"planting")) else null
        val planting=plantingEvidence?.read()
        if(definition is PlantingTaskDefinition) {
            require(plantingEvidence?.definition() == definition && planting?.resources?.entries == resources && planting.deliveries == deliveries) { "archived planting differs from its physical report" }
        } else if(plantingEvidence != null) {
            val requested=(definition as? LumberjackTaskDefinition)?.replant ?: throw IllegalArgumentException("unexpected planting stage evidence")
            val actual=plantingEvidence.definition()
            require(WoodReplant.matchesRequested(actual,requested,actual.work.sites.flatMap(actual.work.species::footprint).toSet())) { "archived wood planting escaped its supplied contract" }
            require(checkNotNull(planting).resources.retained() == resources.filterValues { it.retained > 0 }.mapValues { it.value.retained } && planting.planted() <= (resources[actual.work.species.blockId]?.consumed ?: 0)) { "archived wood planting lacks its physical resources" }
        }
        val expected = when (definition) {
            is PlantingTaskDefinition -> checkNotNull(planting).completed(definition)
            is FarmTaskDefinition -> checkNotNull(farming).delivered(definition)
            is FoodTaskDefinition -> checkNotNull(food).delivered(definition)
            is MiningTaskDefinition -> checkNotNull(mining).confirmed(definition)
            is TransportTaskDefinition -> resources[definition.itemId]?.delivered ?: 0
            is DeliveryTaskDefinition -> resources[definition.itemId]?.delivered ?: 0
            is LumberjackTaskDefinition -> HarvestResources.restore(resources, false).delivered(definition.wood)
            else -> 0
        }
        require(confirmed == expected) { "objective credit differs from its resource/counting basis" }
        return TaskObjectiveReport(tag.getUUID("objective"), revision, definition, confirmed, resources, deliveries, tag.getString("detail"), miningEvidence, foodEvidence, farmEvidence, plantingEvidence)
    }
    fun resources(values: Map<String, HarvestResource>) = ListTag().apply { for ((id, value) in values.toSortedMap()) add(CompoundTag().apply {
        putString("item", id); putInt("initial", value.initial); putInt("gathered", value.gathered); putInt("supplied", value.supplied)
        putInt("consumed", value.consumed); putInt("delivered", value.delivered); putInt("lost", value.lost); putInt("retained", value.retained)
    }) }
    fun readResources(tag: CompoundTag): Map<String, HarvestResource> {
        val values = linkedMapOf<String, HarvestResource>()
        for (raw in list(tag, "resources", HarvestResources.MAX_KINDS)) {
            val row = raw as CompoundTag
            require(row.allKeys == setOf("item", "initial", "gathered", "supplied", "consumed", "delivered", "lost", "retained")) { "invalid resource report" }
            val value = HarvestResource(TaskChangeCodec.integer(row, "initial"), TaskChangeCodec.integer(row, "gathered"), TaskChangeCodec.integer(row, "supplied"),
                TaskChangeCodec.integer(row, "consumed"), TaskChangeCodec.integer(row, "delivered"), TaskChangeCodec.integer(row, "lost"), TaskChangeCodec.integer(row, "retained"))
            require(values.put(row.getString("item"), value) == null) { "duplicate resource report" }
        }
        return HarvestResources.restore(values, false).entries
    }
    fun deliveries(values: Map<NpcBlockPosition, Map<String, Int>>) = ListTag().apply {
        for ((position, counts) in values.toSortedMap(compareBy<NpcBlockPosition> { it.x }.thenBy { it.y }.thenBy { it.z })) add(CompoundTag().apply {
            put("position", LumberjackTaskCodec.position(position)); put("counts", LumberjackTaskCodec.counts(counts))
        })
    }
    fun readDeliveries(tag: CompoundTag): Map<NpcBlockPosition, Map<String, Int>> {
        val rows = linkedMapOf<NpcBlockPosition, Map<String, Int>>()
        for (raw in list(tag, "deliveries", 32)) {
            val row = raw as CompoundTag
            require(row.allKeys == setOf("position", "counts")) { "invalid recipient report" }
            val position = LumberjackTaskCodec.readPosition(TaskChangeCodec.compound(row, "position"))
            require(position.x in -29_999_984..29_999_984 && position.y in -2048..2048 && position.z in -29_999_984..29_999_984) { "recipient report coordinate is out of bounds" }
            require(rows.put(position, LumberjackTaskCodec.readCounts(row, "counts")) == null) { "duplicate recipient report" }
        }
        return java.util.Map.copyOf(rows)
    }
    internal fun list(tag: CompoundTag, key: String, limit: Int): ListTag {
        val list = tag.get(key) as? ListTag ?: throw IllegalArgumentException("missing report list $key")
        require(list.size <= limit && (list.isEmpty() || list.elementType == Tag.TAG_COMPOUND)) { "oversized/invalid report list $key" }; return list
    }
}
