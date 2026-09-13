package io.samcnpc.behavior.api

import io.samcnpc.behavior.registry.RegisteredBehaviorCatalog

/** Immutable code-defined component metadata; this gateway cannot assign or execute work. */
object BehaviorCatalogApi {
    fun snapshot(): BehaviorComponentCatalog = RegisteredBehaviorCatalog.snapshot
}

class BehaviorComponentCatalog internal constructor(
    val catalogVersion: Int,
    val documentVersion: Int,
    val definitionSemanticsVersion: Int,
    conditions: List<BehaviorComponentDescriptor>,
    actions: List<BehaviorComponentDescriptor>,
) {
    val conditions: List<BehaviorComponentDescriptor> = java.util.List.copyOf(conditions)
    val actions: List<BehaviorComponentDescriptor> = java.util.List.copyOf(actions)
}

enum class BehaviorComponentKind { CONDITION, ACTION }

class BehaviorComponentDescriptor internal constructor(
    val id: String,
    val kind: BehaviorComponentKind,
    val version: Int,
    channels: List<String>,
    parameters: List<BehaviorParameter>,
) {
    val channels: List<String> = java.util.List.copyOf(channels)
    val parameters: List<BehaviorParameter> = java.util.List.copyOf(parameters)
}

enum class BehaviorNumericKind { NUMBER, INTEGER }

sealed interface BehaviorNumberDefault {
    data class Fixed(val value: Double) : BehaviorNumberDefault
    data class FromParameter(val parameter: String, val offset: Double) : BehaviorNumberDefault
}

/** Data about permitted inputs, never an executable expression or a mutable JSON object. */
sealed interface BehaviorParameter {
    val name: String
    val required: Boolean
    val description: String

    data class Numeric internal constructor(
        override val name: String,
        override val required: Boolean,
        override val description: String,
        val kind: BehaviorNumericKind,
        val minimum: Double,
        val maximum: Double,
        val defaultValue: BehaviorNumberDefault? = null,
        val greaterThanParameter: String? = null,
    ) : BehaviorParameter

    class Choice internal constructor(
        override val name: String,
        override val required: Boolean,
        override val description: String,
        values: List<String>,
    ) : BehaviorParameter {
        val values: List<String> = java.util.List.copyOf(values)
    }

    data class Flag internal constructor(
        override val name: String,
        override val required: Boolean,
        override val description: String,
        val defaultValue: Boolean?,
    ) : BehaviorParameter
}
