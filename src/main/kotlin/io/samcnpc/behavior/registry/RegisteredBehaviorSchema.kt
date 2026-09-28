package io.samcnpc.behavior.registry

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*

/** Cold tooling path: one bounded bundled resource read; no tick handler or world access. */
internal object RegisteredBehaviorSchema {
    val json: String = create()

    private fun create(): String {
        val stream = checkNotNull(RegisteredBehaviorSchema::class.java.classLoader.getResourceAsStream("samcnpc/behavior-pack.schema.json")) {
            "Bundled structural behavior schema is missing"
        }
        val base = stream.use { BoundedBehaviorJson.read(it) }
        val schema = BoundedBehaviorJson.parse(base).asJsonObject
        val catalog = BehaviorCatalogApi.snapshot()
        schema.addProperty("\$id", "https://samcnpc.invalid/schema/behavior-pack-registered-v1.json")
        schema.addProperty("title", "SAMCNPC registered behavior components v1")
        schema.addProperty("\$comment", "Runtime additionally checks action channels, duplicate rule IDs, condition depth, related arguments, input byte/tree limits and reload/world context. Schema acceptance is not activation.")
        schema.addProperty("x-samcnpc-catalog-version", catalog.catalogVersion)
        schema.addProperty("x-samcnpc-definition-semantics-version", catalog.definitionSemanticsVersion)
        val builtins = JsonArray()
        BehaviorPackLoader.builtinPackIds.forEach(builtins::add)
        schema.add("x-samcnpc-builtins", builtins)
        val definitions = schema.getAsJsonObject("\$defs")
        definitions.add("test", variants(catalog.conditions, "condition"))
        definitions.add("action", variants(catalog.actions, "action"))
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(schema)
    }

    private fun variants(entries: List<BehaviorComponentDescriptor>, idField: String): JsonObject {
        val alternatives = JsonArray()
        for (entry in entries) {
            val objectSchema = JsonObject()
            objectSchema.addProperty("title", entry.id.substringAfter(':').replace('_', ' '))
            objectSchema.addProperty("x-samcnpc-component-version", entry.version)
            val channels = JsonArray()
            entry.channels.forEach(channels::add)
            objectSchema.add("x-samcnpc-required-channels", channels)
            objectSchema.addProperty("type", "object")
            objectSchema.addProperty("additionalProperties", false)
            val required = JsonArray()
            required.add(idField)
            val properties = JsonObject()
            val id = JsonObject()
            id.addProperty("const", entry.id)
            properties.add(idField, id)
            val args = JsonObject()
            args.addProperty("type", "object")
            args.addProperty("additionalProperties", false)
            val argumentProperties = JsonObject()
            val argumentRequired = JsonArray()
            for (parameter in entry.parameters) {
                argumentProperties.add(parameter.name, parameterSchema(parameter))
                if (parameter.required) argumentRequired.add(parameter.name)
            }
            args.add("properties", argumentProperties)
            if (argumentRequired.size() > 0) {
                required.add("args")
                args.add("required", argumentRequired)
            }
            properties.add("args", args)
            objectSchema.add("properties", properties)
            objectSchema.add("required", required)
            alternatives.add(objectSchema)
        }
        val result = JsonObject()
        result.add("oneOf", alternatives)
        return result
    }

    private fun parameterSchema(parameter: BehaviorParameter): JsonObject {
        val result = JsonObject()
        result.addProperty("description", parameter.description)
        when (parameter) {
            is BehaviorParameter.Text -> {
                result.addProperty("type", "string")
                result.addProperty("minLength", parameter.minimumLength)
                result.addProperty("maxLength", parameter.maximumLength)
                result.addProperty("pattern", parameter.pattern)
                result.addProperty("x-samcnpc-example", parameter.example)
                result.addProperty("x-samcnpc-text-format", parameter.format)
            }
            is BehaviorParameter.Numeric -> {
                result.addProperty("type", if (parameter.kind == BehaviorNumericKind.INTEGER) "integer" else "number")
                result.addProperty("minimum", parameter.minimum)
                result.addProperty("maximum", parameter.maximum)
                when (val default = parameter.defaultValue) {
                    is BehaviorNumberDefault.Fixed -> result.addProperty("default", default.value)
                    is BehaviorNumberDefault.FromParameter -> {
                        val relationship = JsonObject()
                        relationship.addProperty("parameter", default.parameter)
                        relationship.addProperty("offset", default.offset)
                        result.add("x-samcnpc-default-from", relationship)
                    }
                    null -> Unit
                }
                parameter.greaterThanParameter?.let { result.addProperty("x-samcnpc-greater-than", it) }
            }
            is BehaviorParameter.Choice -> {
                result.addProperty("type", "string")
                val values = JsonArray()
                for (value in parameter.values) values.add(value)
                result.add("enum", values)
            }
            is BehaviorParameter.Flag -> {
                result.addProperty("type", "boolean")
                parameter.defaultValue?.let { result.addProperty("default", it) }
            }
        }
        return result
    }
}
