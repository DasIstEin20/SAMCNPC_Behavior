package io.samcnpc.behavior.operation

import io.samcnpc.behavior.api.*

/** Code-only catalog construction; no registration, expression language or reflection in input data. */
internal val id = OperationInput.Text(OperationTextFormat.RESOURCE_ID, 256)
internal val uuid = OperationInput.Text(OperationTextFormat.UUID, 36)
internal fun ref(name: String) = OperationInput.Reference(name)
internal fun choice(vararg values: String) = OperationInput.Choice(values.toList())
internal fun integer(min: Int, max: Int, unit: String = "count") = OperationInput.Numeric(true, min.toDouble(), max.toDouble(), unit)
internal fun number(min: Double, max: Double, unit: String = "blocks") = OperationInput.Numeric(false, min, max, unit)
internal fun list(item: OperationInput, min: Int, max: Int, unique: Boolean = false) = OperationInput.Sequence(item, min, max, unique)
internal fun record(vararg fields: OperationField) = OperationInput.Record(fields.toList())
internal fun record(fields: List<OperationField>, vararg rules: OperationRelation) = OperationInput.Record(fields, rules.toList())
internal fun relation(code: String, description: String, vararg paths: String) = OperationRelation(code, paths.toList(), description)
internal fun required(name: String, input: OperationInput, description: String = name) = OperationField(name, input, description)
internal fun optional(name: String, input: OperationInput, defaultJson: String, description: String = name) =
    OperationField(name, input, description, false, false, OperationInputDefault.Literal(defaultJson))
internal fun nullable(name: String, input: OperationInput, description: String = name) =
    OperationField(name, input, description, false, true, OperationInputDefault.Literal("null"))
internal fun inherited(name: String, input: OperationInput, field: String, nullable: Boolean = false, offset: Int = 0, target: String? = null) =
    OperationField(name, input, "Default derives from $field", false, nullable, OperationInputDefault.FromField(field, offset, target))
internal fun budget(ticks: Int = 6000, maxTicks: Int = 72000) = record(
    optional("ticks", integer(20, maxTicks, "ticks"), ticks.toString()),
    optional("attempts", integer(1, 8, "attempts"), "3"),
    optional("backoffTicks", integer(1, 200, "ticks"), "20"),
)
internal fun common(budget: OperationField = optional("budget", ref("budget"), "{}")) =
    listOf(required("dimensionId", id), budget)
internal fun travel(defaultRadius: Double = 64.0) = listOf(
    required("anchor", ref("position")),
    optional("travelRadius", number(4.0, 64.0), defaultRadius.toString()),
    nullable("returnTo", ref("position")),
)
internal val localEndpoints = relation("LOCAL_ENDPOINTS",
    "Every work/recipient/source/return point must be within travelRadius of anchor and the family-specific local observation diameter.",
    "anchor", "travelRadius", "returnTo", "work", "destinations")
