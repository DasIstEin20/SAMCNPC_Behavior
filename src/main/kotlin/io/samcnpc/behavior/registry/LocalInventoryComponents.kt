package io.samcnpc.behavior.registry

import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.inventory.*
import io.samcnpc.behavior.model.*
import io.samcnpc.behavior.task.TaskReason
import io.samcnpc.behavior.task.TaskStatus
import io.samcnpc.core.api.*

/** Parameterized registered components share their validation metadata with schema/editor exports. */
internal object LocalInventoryComponents {
    private val query = BehaviorParameter.Text("query", true, "Exact ID, authoritative @role, or 2..8 explicit alternatives separated by |. Coal never implies charcoal.", 1, 512, ItemQuery.PATTERN, "@axe")
    private val comparison = BehaviorParameter.Choice("operator", true, "Numeric comparison.", listOf("gt", "gte", "lt", "lte", "eq"))
    private val destination = BehaviorParameter.Choice("destination", true, "Physical equipment destination; main hand aliases the selected hotbar slot.", NpcEquipmentDestination.entries.map { it.name })
    private val durability = number("minimumDurability", 0.0, 1.0, "Minimum remaining durability fraction; nondamageable items have fraction 1.", required = false, default = 0.0)
    val conditionParameters = mapOf(
        "samcnpc:inventory_count" to listOf(query, comparison, number("count", 0.0, 4096.0, "Carried and separately equipped item count, without double-counting the selected hand.", integer = true), durability),
        "samcnpc:inventory_free_slots" to listOf(comparison, number("count", 0.0, 36.0, "Empty carried slots; partial stacks are not promised NBT-compatible capacity.", integer = true)),
        "samcnpc:equipment_matches" to listOf(query, destination, durability),
        "samcnpc:durability_fraction" to listOf(destination, comparison, number("value", 0.0, 1.0, "Remaining durability; empty equipment never satisfies this condition.")),
        "samcnpc:at_position" to listOf(number("x", -29999984.0, 29999984.0, "Feet position X."), number("y", -2048.0, 2048.0, "Feet position Y."),
            number("z", -29999984.0, 29999984.0, "Feet position Z."), number("radius", 0.0, 64.0, "Three-dimensional feet distance.")),
        "samcnpc:task_status" to listOf(BehaviorParameter.Choice("status", true, "Current durable task status; no task never matches.", TaskStatus.entries.map { it.name })),
        "samcnpc:task_attempts_remaining" to listOf(comparison, number("count", 0.0, 8.0, "Remaining attempts in the current durable frame.", integer = true)),
        "samcnpc:last_task_failure" to listOf(BehaviorParameter.Choice("reason", true, "Failure reason of the current durable frame/task, only when a failure was recorded.", TaskReason.entries.map { it.name })),
    )
    val actionParameters = mapOf("samcnpc:ensure_equipment" to listOf(query, destination, durability))
    val conditions: List<ConditionDefinition> = conditionParameters.map { (id, parameters) ->
        ConditionDefinition(id, { validate(it, parameters) }) { args ->
            when (id) {
                "samcnpc:inventory_count" -> {
                    val selected = ItemQuery.parse(args.get("query").asString); val limit = minimum(args)
                    val operator = args.get("operator").asString; val count = args.get("count").asInt
                    LocalFactCondition(needsInventory = true) { c -> c.inventoryFacts?.let { compare(operator, it.count(selected, limit).toDouble(), count.toDouble()) } == true }
                }
                "samcnpc:inventory_free_slots" -> {
                    val operator = args.get("operator").asString; val count = args.get("count").asInt
                    LocalFactCondition(needsInventory = true) { c -> c.inventoryFacts?.let { compare(operator, it.freeSlots.toDouble(), count.toDouble()) } == true }
                }
                "samcnpc:equipment_matches" -> {
                    val selected = ItemQuery.parse(args.get("query").asString); val target = target(args); val limit = minimum(args)
                    LocalFactCondition(needsInventory = true) { it.inventoryFacts?.equippedMatches(selected, target, limit) == true }
                }
                "samcnpc:durability_fraction" -> {
                    val target = target(args); val operator = args.get("operator").asString; val expected = args.get("value").asDouble
                    LocalFactCondition(needsInventory = true) { c -> c.inventoryFacts?.equipped(target)?.first?.let { !it.isEmpty && compare(operator, ItemQuery.durability(it), expected) } == true }
                }
                "samcnpc:at_position" -> {
                    val x = args.get("x").asDouble; val y = args.get("y").asDouble; val z = args.get("z").asDouble; val radius = args.get("radius").asDouble
                    ConditionHandler { c -> val p = c.snapshot.position; (p.x-x)*(p.x-x)+(p.y-y)*(p.y-y)+(p.z-z)*(p.z-z) <= radius*radius }
                }
                "samcnpc:task_status" -> { val value = args.get("status").asString; LocalFactCondition(needsTask = true) { it.localTaskFacts?.status == value } }
                "samcnpc:task_attempts_remaining" -> {
                    val operator = args.get("operator").asString; val count = args.get("count").asInt
                    LocalFactCondition(needsTask = true) { c -> c.localTaskFacts?.let { compare(operator, it.attemptsRemaining.toDouble(), count.toDouble()) } == true }
                }
                else -> { val reason = args.get("reason").asString; LocalFactCondition(needsTask = true) { it.localTaskFacts?.lastFailure == reason } }
            }
        }
    }
    val actions = listOf(ActionDefinition("samcnpc:ensure_equipment",
        setOf(BehaviorChannel.INVENTORY, BehaviorChannel.MAIN_HAND, BehaviorChannel.OFF_HAND),
        { validate(it, actionParameters.getValue("samcnpc:ensure_equipment")) }) { args ->
        val selected = ItemQuery.parse(args.get("query").asString); val target = target(args); val limit = minimum(args)
        ActionHandler { npc, _, _ -> EquipmentPreparation.ensure(npc, selected, target, limit) }
    })

    internal fun validate(args: JsonObject, parameters: List<BehaviorParameter>): String? {
        if (args.keySet().any { name -> parameters.none { it.name == name } }) return "unknown argument"
        for (parameter in parameters) {
            val value = args.get(parameter.name)
            if (value == null) { if (parameter.required) return "requires ${parameter.name}"; continue }
            if (!value.isJsonPrimitive) return "${parameter.name} has the wrong type"
            val primitive = value.asJsonPrimitive
            val valid = when (parameter) {
                is BehaviorParameter.Numeric -> primitive.isNumber && if (parameter.kind == BehaviorNumericKind.INTEGER) value.exactLongOrNull()?.let { it.toDouble() in parameter.minimum..parameter.maximum } == true
                    else primitive.asDouble.isFinite() && primitive.asDouble in parameter.minimum..parameter.maximum
                is BehaviorParameter.Choice -> primitive.isString && primitive.asString in parameter.values
                is BehaviorParameter.Flag -> primitive.isBoolean
                is BehaviorParameter.Text -> primitive.isString && primitive.asString.length in parameter.minimumLength..parameter.maximumLength &&
                    try { ItemQuery.parse(primitive.asString); true } catch (_: IllegalArgumentException) { false }
            }
            if (!valid) return "invalid ${parameter.name}"
        }
        return null
    }
    private fun target(args: JsonObject) = NpcEquipmentDestination.valueOf(args.get("destination").asString)
    private fun minimum(args: JsonObject) = args.get("minimumDurability")?.asDouble ?: 0.0
    internal fun compare(operator: String, actual: Double, expected: Double) = when (operator) {
        "gt" -> actual > expected; "gte" -> actual >= expected; "lt" -> actual < expected; "lte" -> actual <= expected; "eq" -> actual == expected
        else -> false
    }
    private fun number(name: String, min: Double, max: Double, description: String, integer: Boolean = false, required: Boolean = true, default: Double? = null) =
        BehaviorParameter.Numeric(name, required, description, if (integer) BehaviorNumericKind.INTEGER else BehaviorNumericKind.NUMBER, min, max, default?.let { BehaviorNumberDefault.Fixed(it) })
}
