package io.samcnpc.behavior.registry

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.samcnpc.behavior.api.ValidationReport
import io.samcnpc.behavior.model.ActionDefinition
import io.samcnpc.behavior.model.BehaviorChannel
import io.samcnpc.behavior.model.CompiledAction
import io.samcnpc.behavior.model.CompiledPack
import io.samcnpc.behavior.model.CompiledRule
import io.samcnpc.behavior.model.ConditionDefinition
import io.samcnpc.behavior.model.ConditionExpression

data class CompileResult(
    val pack: CompiledPack?,
    val report: ValidationReport,
)

/** Strict v1 structural and semantic validation; no class names or executable data can enter. */
class BehaviorPackCompiler(
    private val conditions: Map<String, ConditionDefinition>,
    private val actions: Map<String, ActionDefinition>,
) {
    fun compile(source: String, json: String): CompileResult {
        val root = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
                ?: return rejected(source, "root must be an object")
        } catch (error: com.google.gson.JsonParseException) {
            return rejected(source, "malformed JSON: ${error.message}")
        }
        return try {
            val pack = parsePack(source, root)
            CompileResult(pack, ValidationReport(true, listOf("$source: accepted ${pack.id}")))
        } catch (error: PackValidationException) {
            rejected(source, error.message ?: "validation failed")
        }
    }

    private fun parsePack(source: String, root: JsonObject): CompiledPack {
        root.onlyKeys(source, setOf("schemaVersion", "id", "description", "priority", "channels", "rules"))
        if (root.requireInt(source, "schemaVersion", 1, 1) != 1) {
            fail(source, "schemaVersion must be 1")
        }
        val id = root.requireId(source, "id", PACK_ID, 128)
        val description = root.requireString(source, "description", 512)
        val priority = root.requireInt(source, "priority", -100_000, 100_000)
        val channels = root.requireArray(source, "channels", 1, 16).mapIndexed { index, element ->
            val value = element.stringOrNull() ?: fail(source, "channels[$index] must be a string")
            BehaviorChannel.parse(value) ?: fail(source, "channels[$index] is not a supported channel")
        }.toSet()
        if (channels.size != root.getAsJsonArray("channels").size()) {
            fail(source, "channels must be unique")
        }
        val rulesArray = root.requireArray(source, "rules", 1, 256)
        val rules = rulesArray.mapIndexed { index, element -> parseRule(source, index, element.objectOrFail(source, "rules[$index]"), channels) }
        if (rules.map { it.id }.toSet().size != rules.size) {
            fail(source, "rule IDs must be unique within a pack")
        }
        return CompiledPack(id, description, priority, channels, rules)
    }

    private fun parseRule(source: String, index: Int, value: JsonObject, allowedChannels: Set<BehaviorChannel>): CompiledRule {
        val prefix = "$source rule[$index]"
        value.onlyKeys(prefix, setOf("id", "priority", "cooldownTicks", "when", "actions"))
        val id = value.requireId(prefix, "id", RULE_ID, 96)
        val priority = value.requireInt(prefix, "priority", -100_000, 100_000)
        val cooldown = if (value.has("cooldownTicks")) value.requireInt(prefix, "cooldownTicks", 0, 120_000) else 0
        val expression = parseExpression(prefix, value.requireObject(prefix, "when"), 0)
        val actionArray = value.requireArray(prefix, "actions", 1, 16)
        val compiledActions = actionArray.mapIndexed { actionIndex, element ->
            parseAction("$prefix action[$actionIndex]", element.objectOrFail(prefix, "actions[$actionIndex]"), allowedChannels)
        }
        return CompiledRule(id, priority, cooldown, expression, compiledActions)
    }

    private fun parseExpression(context: String, value: JsonObject, depth: Int): ConditionExpression {
        if (depth >= MAX_EXPRESSION_DEPTH) {
            fail(context, "condition expression exceeds depth $MAX_EXPRESSION_DEPTH")
        }
        value.onlyKeys(context, setOf("test", "all", "any", "not"))
        val present = listOf("test", "all", "any", "not").filter(value::has)
        if (present.size != 1) {
            fail(context, "condition expression needs exactly one of test/all/any/not")
        }
        return when (present.single()) {
            "test" -> {
                val test = value.requireObject(context, "test")
                test.onlyKeys("$context test", setOf("condition", "args"))
                val conditionId = test.requireId("$context test", "condition", PACK_ID, 128)
                val definition = conditions[conditionId] ?: fail(context, "unknown condition '$conditionId'")
                val args = test.optionalObject("$context test", "args")
                definition.validateArgs(args)?.let { fail(context, "condition '$conditionId' $it") }
                ConditionExpression.Test(conditionId, args.deepCopy())
            }
            "all" -> ConditionExpression.All(parseChildren(context, value.requireArray(context, "all", 1, 16), depth))
            "any" -> ConditionExpression.Any(parseChildren(context, value.requireArray(context, "any", 1, 16), depth))
            "not" -> ConditionExpression.Not(parseExpression(context, value.requireObject(context, "not"), depth + 1))
            else -> error("unreachable")
        }
    }

    private fun parseChildren(context: String, array: JsonArray, depth: Int): List<ConditionExpression> =
        array.mapIndexed { index, element -> parseExpression("$context expression[$index]", element.objectOrFail(context, "expression[$index]"), depth + 1) }

    private fun parseAction(context: String, value: JsonObject, allowedChannels: Set<BehaviorChannel>): CompiledAction {
        value.onlyKeys(context, setOf("action", "args"))
        val actionId = value.requireId(context, "action", PACK_ID, 128)
        val definition: ActionDefinition = actions[actionId] ?: fail(context, "unknown action '$actionId'")
        if (!allowedChannels.containsAll(definition.channels)) {
            fail(context, "action '$actionId' requests ${definition.channels} outside the pack channels")
        }
        val args = value.optionalObject(context, "args")
        definition.validateArgs(args)?.let { fail(context, "action '$actionId' $it") }
        return CompiledAction(actionId, args.deepCopy(), definition.channels)
    }

    private fun rejected(source: String, message: String): CompileResult =
        CompileResult(null, ValidationReport(false, listOf("$source: $message")))

    private fun JsonObject.onlyKeys(context: String, allowed: Set<String>) {
        entrySet().firstOrNull { it.key !in allowed }?.let { fail(context, "unknown property '${it.key}'") }
    }

    private fun JsonObject.requireObject(context: String, name: String): JsonObject =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: fail(context, "'$name' must be an object")

    private fun JsonObject.optionalObject(context: String, name: String): JsonObject {
        if (!has(name)) {
            return JsonObject()
        }
        return get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: fail(context, "'$name' must be an object")
    }

    private fun JsonObject.requireArray(context: String, name: String, minimum: Int, maximum: Int): JsonArray {
        val value = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: fail(context, "'$name' must be an array")
        if (value.size() !in minimum..maximum) {
            fail(context, "'$name' must contain $minimum..$maximum entries")
        }
        return value
    }

    private fun JsonObject.requireString(context: String, name: String, maximum: Int): String {
        val result = get(name).stringOrNull() ?: fail(context, "'$name' must be a string")
        if (result.length > maximum) {
            fail(context, "'$name' exceeds $maximum characters")
        }
        return result
    }

    private fun JsonObject.requireId(context: String, name: String, pattern: Regex, maximum: Int): String {
        val value = requireString(context, name, maximum)
        if (!pattern.matches(value)) {
            fail(context, "'$name' is not a valid namespaced ID")
        }
        return value
    }

    private fun JsonObject.requireInt(context: String, name: String, minimum: Int, maximum: Int): Int {
        val primitive = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asJsonPrimitive
            ?: fail(context, "'$name' must be an integer")
        val value = primitive.asNumber.toDouble()
        if (!value.isFinite() || value != value.toInt().toDouble() || value !in minimum.toDouble()..maximum.toDouble()) {
            fail(context, "'$name' must be an integer in [$minimum, $maximum]")
        }
        return value.toInt()
    }

    private fun JsonElement.objectOrFail(context: String, label: String): JsonObject =
        takeIf { isJsonObject }?.asJsonObject ?: fail(context, "'$label' must be an object")

    private fun JsonElement.stringOrNull(): String? =
        takeIf { isJsonPrimitive && asJsonPrimitive.isString }?.asString

    private fun fail(context: String, detail: String): Nothing = throw PackValidationException("$context: $detail")

    private class PackValidationException(message: String) : IllegalArgumentException(message)

    companion object {
        private const val MAX_EXPRESSION_DEPTH = 8
        private val PACK_ID = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
        private val RULE_ID = Regex("^[a-z0-9_.-]+$")
    }
}
