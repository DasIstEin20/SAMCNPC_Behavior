package io.samcnpc.behavior.api

import io.samcnpc.behavior.operation.RegisteredOperationCatalog

/** Cached immutable discovery data. Reading a catalog never grants authority or executes work. */
object OperationCatalogApi {
    fun snapshot(): OperationCatalog = RegisteredOperationCatalog.snapshot
}

class OperationCatalog internal constructor(
    val catalogVersion: Int,
    val documentVersion: Int,
    operations: List<OperationDescriptor>,
    shapes: Map<String, OperationInput>,
    changes: Map<String, OperationInput>,
    taskStates: List<String>,
    taskReasons: List<String>,
) {
    val operations: List<OperationDescriptor> = java.util.List.copyOf(operations)
    val shapes: Map<String, OperationInput> = java.util.Collections.unmodifiableMap(LinkedHashMap(shapes))
    val changes: Map<String, OperationInput> = java.util.Collections.unmodifiableMap(LinkedHashMap(changes))
    val taskStates: List<String> = java.util.List.copyOf(taskStates)
    val taskReasons: List<String> = java.util.List.copyOf(taskReasons)
}

class OperationDescriptor internal constructor(
    val type: OperationType,
    val description: String,
    val parameters: OperationInput.Record,
    val completion: String,
    amendments: List<String>,
) {
    /** Candidate change kinds; task state, objective history and authority still restrict admission. */
    val amendments: List<String> = java.util.List.copyOf(amendments)
}

class OperationField internal constructor(
    val name: String,
    val input: OperationInput,
    val description: String,
    val required: Boolean = true,
    val nullable: Boolean = false,
    val defaultValue: OperationInputDefault? = null,
)

sealed interface OperationInputDefault {
    /** Bounded JSON literal, rather than a mutable Gson value crossing module boundaries. */
    @ConsistentCopyVisibility
    data class Literal internal constructor(val json: String) : OperationInputDefault
    /** A documented relationship, never an executable expression. Null target means the whole field. */
    @ConsistentCopyVisibility
    data class FromField internal constructor(val field: String, val offset: Int = 0, val target: String? = null) : OperationInputDefault
}

class OperationRelation internal constructor(val code: String, paths: List<String>, val description: String) {
    val paths: List<String> = java.util.List.copyOf(paths)
}

enum class OperationTextFormat { RESOURCE_ID, UUID, ITEM_QUERY }

sealed interface OperationInput {
    data object Flag : OperationInput
    @ConsistentCopyVisibility
    data class Numeric internal constructor(val integer: Boolean, val minimum: Double, val maximum: Double, val unit: String) : OperationInput
    @ConsistentCopyVisibility
    data class Text internal constructor(val format: OperationTextFormat, val maxLength: Int) : OperationInput
    class Choice internal constructor(values: List<String>) : OperationInput {
        val values: List<String> = java.util.List.copyOf(values)
    }
    @ConsistentCopyVisibility
    data class Sequence internal constructor(val item: OperationInput, val minimum: Int, val maximum: Int, val unique: Boolean = false) : OperationInput
    class Record internal constructor(fields: List<OperationField>, relations: List<OperationRelation> = emptyList()) : OperationInput {
        val fields: List<OperationField> = java.util.List.copyOf(fields)
        val relations: List<OperationRelation> = java.util.List.copyOf(relations)
    }
    @ConsistentCopyVisibility
    data class Reference internal constructor(val name: String) : OperationInput
    class Alternatives internal constructor(names: List<String>) : OperationInput {
        val names: List<String> = java.util.List.copyOf(names)
    }
}
