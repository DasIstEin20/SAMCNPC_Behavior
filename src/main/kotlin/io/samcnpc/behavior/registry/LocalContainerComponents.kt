package io.samcnpc.behavior.registry

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.inventory.*
import com.google.gson.JsonObject

internal object LocalContainerComponents {
    private val endpoint = listOf(
        BehaviorParameter.Choice("endpoint", true, "An explicitly permitted SOURCE or DESTINATION of the current inventory task/logistics/work contract; no arbitrary coordinates.", listOf("SOURCE", "DESTINATION")),
        BehaviorParameter.Numeric("index", true, "Zero-based endpoint in that contract. Unknown/unobserved never matches numeric comparisons.", BehaviorNumericKind.INTEGER, 0.0, 7.0),
    )
    private val comparison = listOf(
        BehaviorParameter.Choice("operator", true, "Compare the last authorized observation (at most 20 ticks old); transfers always reobserve.", listOf("gt", "gte", "lt", "lte", "eq")),
        BehaviorParameter.Numeric("count", true, "Required observed item count or empty slot count.", BehaviorNumericKind.INTEGER, 0.0, 4096.0),
    )
    val parameters = mapOf(
        "samcnpc:container_observed" to endpoint,
        "samcnpc:container_count" to endpoint + comparison + BehaviorParameter.Text("query", true, "Exact ID, authoritative @role, or explicit alternatives separated by |.", 1, 512, ItemQuery.PATTERN, "minecraft:coal"),
        "samcnpc:container_free_slots" to endpoint + comparison,
    )
    val conditions = parameters.map { (id, parameters) ->
        ConditionDefinition(id, { LocalInventoryComponents.validate(it, parameters) }) { args: JsonObject ->
            val request = ContainerFactRequest(ContainerFactEndpoint.valueOf(args.get("endpoint").asString), args.get("index").asInt)
            if (id == "samcnpc:container_observed") LocalFactCondition(container = request) { request in it.containerFacts }
            else {
                val query = args.get("query")?.asString?.let(ItemQuery::parse)
                val comparison = args.get("operator").asString; val count = args.get("count").asInt
                LocalFactCondition(container = request) { context ->
                    context.containerFacts[request]?.let { observed ->
                        val actual = if (query == null) observed.slots.count { it.stack.isEmpty }
                            else observed.slots.sumOf { if (query.matches(it.stack, it.knowledge)) it.stack.count else 0 }
                        LocalInventoryComponents.compare(comparison, actual.toDouble(), count.toDouble())
                    } == true
                }
            }
        }
    }
}
