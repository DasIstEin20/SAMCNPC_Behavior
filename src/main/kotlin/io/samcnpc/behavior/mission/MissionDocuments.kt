package io.samcnpc.behavior.mission

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.registry.BoundedBehaviorJson
import io.samcnpc.core.api.*
import java.security.MessageDigest

/** Cold document boundary. No predicate text, IDs or operations are parsed in the tick path. */
internal object MissionDocuments {
    const val MAX_STAGES = 16
    const val MAX_REQUIREMENTS = 16
    private val localId = Regex("[a-z0-9_]{1,48}")

    fun read(body: String): MissionDefinition {
        val root = obj(BoundedBehaviorJson.parse(body), setOf("documentType", "documentVersion", "id", "dimensionId", "requirements", "stages", "guards"))
        require(text(root, "documentType") == "samcnpc:mission" && integer(root, "documentVersion", 1, 1) == 1)
        val id = text(root, "id"); require(ItemQuery.validItemId(id)) { "invalid mission ID" }
        val dimension = text(root, "dimensionId"); require(ItemQuery.validItemId(dimension))
        val requirements = array(root, "requirements", 1, MAX_REQUIREMENTS).map { value ->
            val r = obj(value, setOf("id", "predicate"))
            MissionRequirement(identifier(text(r, "id")), predicate(r.get("predicate")))
        }
        require(requirements.map { it.id }.distinct().size == requirements.size) { "duplicate requirement ID" }
        val stages = array(root, "stages", 1, MAX_STAGES).map { value ->
            val s = obj(value, setOf("id", "pack", "operation", "completion", "timeoutTicks", "success", "retries"))
            val pack = text(s, "pack"); require(ItemQuery.validItemId(pack))
            require(integer(s, "retries", 0, 0) == 0) { "automatic stage retries are unsupported" }
            val order = if (!s.has("operation")) null else when (val decoded = OperationDocumentApi.decodeOrder(s.get("operation").toString())) {
                is OperationDocumentResult.Accepted -> decoded.value
                is OperationDocumentResult.Rejected -> throw IllegalArgumentException("${decoded.code}: ${decoded.detail}")
            }
            require(order == null || order.dimensionId == dimension) { "operation dimension differs from mission" }
            val completion = array(s, "completion", 1, MAX_REQUIREMENTS).map { string(it) }
            require(completion.distinct().size == completion.size && completion.all { name -> requirements.any { it.id == name } }) { "duplicate or missing completion reference" }
            MissionStage(identifier(text(s, "id")), pack, order, java.util.List.copyOf(completion),
                integer(s, "timeoutTicks", 20, 72000), s.get("success")?.takeUnless { it.isJsonNull }?.let { identifier(string(it)) })
        }
        require(stages.map { it.id }.distinct().size == stages.size) { "duplicate stage ID" }
        val visited = hashSetOf<String>()
        var next: String? = stages.first().id
        while (next != null) {
            val stage = stages.singleOrNull { it.id == next } ?: throw IllegalArgumentException("missing success destination: $next")
            require(visited.add(stage.id)) { "mission cycle" }
            next = stage.success
        }
        require(visited.size == stages.size) { "unreachable stage" }
        for (r in requirements) {
            require(stages.any { r.id in it.completion }) { "requirement ${r.id} has no completion checkpoint" }
            val predicate = r.predicate
            if (predicate is MissionPredicate.TaskSuccess) {
                require(stages.any { it.id == predicate.stageId && it.order != null && r.id in it.completion }) { "task success requires its exact operation stage" }
            }
        }
        val guards = if (root.has("guards")) array(root, "guards", 0, 3).map(::string) else emptyList()
        require(guards.distinct().size == guards.size && guards.all(ItemQuery::validItemId))
        require(stages.none { it.pack in guards }) { "main pack cannot also be a guard" }
        return MissionDefinition(id, dimension, body, hash(body), java.util.List.copyOf(requirements), java.util.List.copyOf(stages), java.util.List.copyOf(guards))
    }

    private fun predicate(value: JsonElement?): MissionPredicate {
        require(value != null && value.isJsonObject) { "predicate must be an object" }
        val p = value.asJsonObject
        return when (text(p, "type")) {
            "inventory_count" -> {
                keys(p, setOf("type", "query", "count", "minimumDurability"))
                MissionPredicate.Inventory(ItemQuery.parse(text(p, "query")), integer(p, "count", 1, 4096), number(p, "minimumDurability", 0.0, 1.0))
            }
            "equipment_matches" -> {
                keys(p, setOf("type", "query", "destination", "minimumDurability"))
                MissionPredicate.Equipment(ItemQuery.parse(text(p, "query")), NpcEquipmentDestination.valueOf(text(p, "destination")), number(p, "minimumDurability", 0.0, 1.0))
            }
            "at_position" -> {
                keys(p, setOf("type", "position", "radius"))
                MissionPredicate.Arrival(position(p.get("position")), number(p, "radius", 0.25, 2.0))
            }
            "destination_count" -> {
                keys(p, setOf("type", "position", "itemId", "count"))
                MissionPredicate.Stock(NpcStockQuery(block(p.get("position")), text(p, "itemId")), integer(p, "count", 1, 4096))
            }
            "soil_prepared" -> {
                keys(p, setOf("type", "cells"))
                val cells = array(p, "cells", 1, 64).map(::block)
                require(cells.distinct().size == cells.size && cells.map { it.y }.distinct().size == 1)
                require(cells.maxOf { it.x } - cells.minOf { it.x } <= 63 && cells.maxOf { it.z } - cells.minOf { it.z } <= 63)
                MissionPredicate.Soil(java.util.List.copyOf(cells))
            }
            "task_success" -> {
                keys(p, setOf("type", "stageId"))
                MissionPredicate.TaskSuccess(identifier(text(p, "stageId")))
            }
            else -> throw IllegalArgumentException("unsupported mission predicate")
        }
    }

    private fun position(value: JsonElement?): NpcPosition {
        val p = obj(value, setOf("x", "y", "z"))
        return NpcPosition(number(p,"x",-29999984.0,29999984.0),number(p,"y",-2048.0,2048.0),number(p,"z",-29999984.0,29999984.0))
    }
    private fun block(value: JsonElement?): NpcBlockPosition {
        val p = obj(value, setOf("x", "y", "z"))
        return NpcBlockPosition(integer(p,"x",-29999984,29999984),integer(p,"y",-2048,2048),integer(p,"z",-29999984,29999984))
    }
    private fun identifier(value: String): String { require(localId.matches(value)) { "invalid local ID" }; return value }
    internal fun obj(value: JsonElement?, allowed: Set<String>): JsonObject {
        require(value != null && value.isJsonObject) { "expected object" }; val result = value.asJsonObject
        keys(result, allowed); return result
    }
    internal fun keys(value: JsonObject, allowed: Set<String>) { require(value.keySet().all { it in allowed }) { "unknown document field" } }
    internal fun text(value: JsonObject, key: String): String = string(value.get(key))
    internal fun string(value: JsonElement?): String { require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "expected string" }; return value.asString }
    internal fun integer(value: JsonObject, key: String, min: Int, max: Int): Int {
        val n = value.get(key); require(n != null && n.isJsonPrimitive && n.asJsonPrimitive.isNumber) { "$key must be integer" }
        val result = try { n.asBigDecimal.intValueExact() } catch (error: ArithmeticException) { throw IllegalArgumentException("$key must be integer",error) }
        require(result in min..max) { "$key outside $min..$max" }; return result
    }
    private fun number(value: JsonObject, key: String, min: Double, max: Double): Double {
        val n=value.get(key); require(n != null && n.isJsonPrimitive && n.asJsonPrimitive.isNumber) { "$key must be numeric" }
        val result=n.asDouble; require(result.isFinite() && result in min..max); return result
    }
    internal fun array(value: JsonObject, key: String, min: Int, max: Int): List<JsonElement> {
        val a=value.get(key); require(a != null && a.isJsonArray && a.asJsonArray.size() in min..max) { "$key requires $min..$max entries" }
        return a.asJsonArray.toList()
    }
    internal fun hash(body: String): String = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
