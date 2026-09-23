package io.samcnpc.behavior.operation

import com.google.gson.*
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.registry.BoundedBehaviorJson
import java.math.BigDecimal
import java.util.UUID

/** Structural validation and defaults share the exact nodes exported as JSON Schema. */
internal object OperationJsonShape {
    private val resourceId = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
    fun normalize(value: JsonElement, input: OperationInput, path: String = "$"): JsonElement = when (input) {
        is OperationInput.Reference -> normalize(value, RegisteredOperationCatalog.snapshot.shapes.getValue(input.name), path)
        is OperationInput.Alternatives -> {
            require(value.isJsonObject) { "$path requires a discriminated object" }
            val candidates = input.names.filter { name ->
                val variant = RegisteredOperationCatalog.snapshot.shapes.getValue(name) as OperationInput.Record
                val discriminator = variant.fields.single { it.name in setOf("kind", "type") && it.input is OperationInput.Choice }
                val actual = value.asJsonObject.get(discriminator.name)
                actual != null && actual.isJsonPrimitive && actual.asJsonPrimitive.isString &&
                    actual.asString in (discriminator.input as OperationInput.Choice).values && matchesFixedNumbers(value.asJsonObject, variant)
            }
            require(candidates.size == 1) { "$path has an unsupported variant; expected " + input.names.joinToString() }
            normalize(value, RegisteredOperationCatalog.snapshot.shapes.getValue(candidates.single()), path)
        }
        is OperationInput.Record -> record(value, input, path)
        is OperationInput.Sequence -> {
            require(value.isJsonArray && value.asJsonArray.size() in input.minimum..input.maximum) { "$path requires ${input.minimum}..${input.maximum} entries" }
            val result = JsonArray()
            value.asJsonArray.forEachIndexed { index, entry -> result.add(normalize(entry, input.item, "$path[$index]")) }
            require(!input.unique || result.toList().distinct().size == result.size()) { "$path entries must be unique" }
            result
        }
        is OperationInput.Numeric -> {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$path requires a number" }
            val number = value.asBigDecimal
            require(number >= BigDecimal.valueOf(input.minimum) && number <= BigDecimal.valueOf(input.maximum)) { "$path outside ${input.minimum}..${input.maximum}" }
            require(!input.integer || number.stripTrailingZeros().scale() <= 0) { "$path requires an integer without rounding" }
            JsonPrimitive(number)
        }
        is OperationInput.Text -> {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$path requires a string" }
            val text = value.asString
            require(text.length <= input.maxLength) { "$path string exceeds ${input.maxLength}" }
            when (input.format) {
                OperationTextFormat.RESOURCE_ID -> require(resourceId.matches(text)) { "$path requires a namespaced resource ID" }
                OperationTextFormat.UUID -> require(text.length == 36 && UUID.fromString(text).toString().equals(text, true)) { "$path requires a canonical UUID" }
            }
            JsonPrimitive(text)
        }
        is OperationInput.Choice -> {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString in input.values) { "$path requires one of ${input.values}" }
            value.deepCopy()
        }
        OperationInput.Flag -> {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$path requires a boolean" }
            value.deepCopy()
        }
    }

    /** Versioned variants can share a type ID; singleton numeric fields disambiguate them. */
    private fun matchesFixedNumbers(value: JsonObject, variant: OperationInput.Record): Boolean {
        for (field in variant.fields) {
            val numeric = field.input as? OperationInput.Numeric ?: continue
            if (numeric.minimum != numeric.maximum) continue
            val actual = value[field.name] ?: return false
            if (!actual.isJsonPrimitive || !actual.asJsonPrimitive.isNumber ||
                actual.asBigDecimal.compareTo(BigDecimal.valueOf(numeric.minimum)) != 0) return false
        }
        return true
    }

    private fun record(value: JsonElement, input: OperationInput.Record, path: String): JsonObject {
        require(value.isJsonObject) { "$path requires an object" }
        val source = value.asJsonObject
        val fields = input.fields.associateBy { it.name }
        require(source.keySet().all(fields::containsKey)) { "$path contains an unknown property" }
        val result = JsonObject()
        // Nondependent fields first, so defaults never depend on field declaration order.
        for (field in input.fields.filter { it.defaultValue !is OperationInputDefault.FromField || source.has(it.name) }) {
            val raw = source.get(field.name) ?: when (val default = field.defaultValue) {
                is OperationInputDefault.Literal -> BoundedBehaviorJson.parse(default.json)
                else -> throw IllegalArgumentException("$path.${field.name} is required")
            }
            result.add(field.name, fieldValue(raw, field, path))
        }
        for (field in input.fields.filter { it.defaultValue is OperationInputDefault.FromField && !source.has(it.name) }) {
            val default = field.defaultValue as OperationInputDefault.FromField
            var raw = checkNotNull(result.get(default.field)) { "Catalog default dependency missing: " + default.field }.deepCopy()
            if (default.offset != 0) raw = JsonPrimitive(raw.asBigDecimal + BigDecimal(default.offset))
            if (default.target != null) raw = JsonObject().also { it.add(default.target, raw) }
            result.add(field.name, fieldValue(raw, field, path))
        }
        return result
    }

    private fun fieldValue(raw: JsonElement, field: OperationField, path: String): JsonElement {
        if (raw.isJsonNull) {
            require(field.nullable) { "$path.${field.name} cannot be null" }
            return JsonNull.INSTANCE
        }
        return normalize(raw, field.input, "$path.${field.name}")
    }
}
