package io.samcnpc.behavior.operation

import com.google.gson.*
import io.samcnpc.behavior.api.*

internal object OperationSchemaExport {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    val orderSchema: String by lazy { document(ref("orderDocument")) }
    val changeSchema: String by lazy { document(changeDocument()) }
    val catalogJson: String by lazy {
        val catalog = RegisteredOperationCatalog.snapshot
        val result = JsonObject()
        result.addProperty("catalogVersion", catalog.catalogVersion)
        result.addProperty("documentVersion", catalog.documentVersion)
        result.add("taskStates", strings(catalog.taskStates))
        result.add("taskReasons", strings(catalog.taskReasons))
        val entries = JsonArray()
        for (entry in catalog.operations) {
            val row = JsonObject()
            row.addProperty("type", entry.type.operationId)
            row.addProperty("definitionVersion", entry.type.definitionVersion)
            row.addProperty("description", entry.description)
            row.addProperty("completion", entry.completion)
            row.add("amendments", strings(entry.amendments))
            row.add("parameters", schema(entry.parameters))
            entries.add(row)
        }
        result.add("operations", entries)
        result.add("$"+"defs", definitions())
        val changes = JsonObject()
        catalog.changes.forEach { (name, type) -> changes.add(name, schema(type)) }
        result.add("changes", changes)
        result.addProperty("validation", "Schema is structural. Decode and validateOrder use existing semantic validators; assignment/amendment still check authority, revisions, expiry, task state and the world.")
        gson.toJson(result)
    }

    fun changeDocument(): OperationInput.Alternatives = OperationInput.Alternatives(RegisteredOperationCatalog.snapshot.changes.keys.map { "change_" + it })

    private fun document(type: OperationInput): String {
        val result = schema(type)
        result.addProperty("$"+"schema", "https://json-schema.org/draft/2020-12/schema")
        result.add("$"+"defs", definitions())
        return gson.toJson(result)
    }

    private fun definitions(): JsonObject {
        val catalog = RegisteredOperationCatalog.snapshot
        val result = JsonObject()
        catalog.shapes.forEach { (name, type) -> result.add(name, schema(type)) }
        catalog.changes.forEach { (name, type) -> result.add("change_" + name, schema(changeEnvelope(name, type))) }
        return result
    }

    fun changeEnvelope(name: String, type: OperationInput) = record(required("documentVersion", integer(1, 1, "version")),
        required("type", choice(name)), required("parameters", type))

    fun schema(input: OperationInput): JsonObject {
        val result = JsonObject()
        when (input) {
            is OperationInput.Reference -> result.addProperty("$"+"ref", "#/$"+"defs/" + input.name)
            is OperationInput.Alternatives -> result.add("oneOf", JsonArray().also { array -> input.names.forEach { array.add(schema(ref(it))) } })
            is OperationInput.Numeric -> {
                result.addProperty("type", if (input.integer) "integer" else "number")
                result.addProperty("minimum", input.minimum); result.addProperty("maximum", input.maximum)
                result.addProperty("x-unit", input.unit)
            }
            is OperationInput.Text -> {
                result.addProperty("type", "string"); result.addProperty("maxLength", input.maxLength)
                if (input.format == OperationTextFormat.ITEM_QUERY) result.addProperty("pattern", ItemQuery.PATTERN)
                else if (input.format == OperationTextFormat.RESOURCE_ID) result.addProperty("pattern", "^[a-z0-9_.-]+:[a-z0-9_./-]+$")
                else { result.addProperty("format", "uuid"); result.addProperty("pattern", "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") }
            }
            is OperationInput.Choice -> { result.addProperty("type", "string"); result.add("enum", strings(input.values)) }
            OperationInput.Flag -> result.addProperty("type", "boolean")
            is OperationInput.Sequence -> {
                result.addProperty("type", "array"); result.addProperty("minItems", input.minimum); result.addProperty("maxItems", input.maximum)
                result.addProperty("uniqueItems", input.unique); result.add("items", schema(input.item))
            }
            is OperationInput.Record -> {
                result.addProperty("type", "object"); result.addProperty("additionalProperties", false)
                val properties = JsonObject()
                for (field in input.fields) {
                    val type = schema(field.input)
                    val property = if (field.nullable) JsonObject().also { it.add("anyOf", JsonArray().also { options ->
                        options.add(type); options.add(JsonObject().also { nullType -> nullType.addProperty("type", "null") })
                    }) } else type
                    property.addProperty("description", field.description)
                    when (val default = field.defaultValue) {
                        is OperationInputDefault.Literal -> property.add("default", JsonParser.parseString(default.json))
                        is OperationInputDefault.FromField -> property.add("x-defaultFrom", JsonObject().also {
                            it.addProperty("field", default.field); it.addProperty("offset", default.offset)
                            default.target?.let { target -> it.addProperty("target", target) }
                        })
                        null -> Unit
                    }
                    properties.add(field.name, property)
                }
                result.add("properties", properties)
                result.add("required", strings(input.fields.filter { it.required }.map { it.name }))
                if (input.relations.isNotEmpty()) result.add("x-semanticRelations", JsonArray().also { relations ->
                    input.relations.forEach { relation -> relations.add(JsonObject().also {
                        it.addProperty("code", relation.code); it.add("paths", strings(relation.paths)); it.addProperty("description", relation.description)
                    }) }
                })
            }
        }
        return result
    }
    private fun strings(values: List<String>) = JsonArray().also { array -> values.forEach(array::add) }
}
