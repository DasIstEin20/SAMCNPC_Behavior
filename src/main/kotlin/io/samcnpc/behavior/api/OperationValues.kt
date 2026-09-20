package io.samcnpc.behavior.api

/** Read-only data for inspection. No parser, expression evaluator or execution entry point. */
sealed interface OperationValue {
    data object Absent : OperationValue
    @ConsistentCopyVisibility
    data class Text internal constructor(val value: String) : OperationValue {
        init { require(value.length <= 256) }
    }
    @ConsistentCopyVisibility
    data class Whole internal constructor(val value: Long) : OperationValue
    @ConsistentCopyVisibility
    data class Decimal internal constructor(val value: Double) : OperationValue {
        init { require(value.isFinite()) }
    }
    @ConsistentCopyVisibility
    data class Flag internal constructor(val value: Boolean) : OperationValue
    class Sequence internal constructor(values: List<OperationValue>) : OperationValue {
        val values: List<OperationValue> = java.util.List.copyOf(values)
        init { require(values.size <= 128) }
    }
    class Record internal constructor(fields: Map<String, OperationValue>) : OperationValue {
        val fields: Map<String, OperationValue> = java.util.Collections.unmodifiableMap(LinkedHashMap(fields))
        init { require(fields.size <= 64 && fields.keys.all { it.isNotEmpty() && it.length <= 64 }) }
    }
}

/** Actual persisted definition version; inspecting a legacy definition never upgrades it. */
class OperationDefinitionSnapshot internal constructor(
    val operationId: String,
    val definitionVersion: Int,
    val parameters: OperationValue.Record,
)
