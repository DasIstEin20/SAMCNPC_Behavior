package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.OperationValue
import io.samcnpc.core.api.*
import java.util.UUID

internal object TaskInspectionValues {
    fun v(value: String?): OperationValue = if (value == null) OperationValue.Absent else OperationValue.Text(value)
    fun v(value: UUID?): OperationValue = v(value?.toString())
    fun v(value: Int): OperationValue = OperationValue.Whole(value.toLong())
    fun v(value: Double): OperationValue = OperationValue.Decimal(value)
    fun v(value: Float): OperationValue = v(value.toDouble())
    fun v(value: Boolean): OperationValue = OperationValue.Flag(value)
    fun v(value: NpcPosition?): OperationValue = if (value == null) OperationValue.Absent
        else record("x" to v(value.x), "y" to v(value.y), "z" to v(value.z))
    fun v(value: NpcBlockPosition?): OperationValue = if (value == null) OperationValue.Absent
        else record("x" to v(value.x), "y" to v(value.y), "z" to v(value.z))
    fun record(vararg fields: Pair<String, OperationValue>): OperationValue.Record {
        val values = linkedMapOf(*fields)
        check(values.size == fields.size) { "Inspection fields must be unique" }
        return OperationValue.Record(values)
    }
    fun sequence(values: List<OperationValue>) = OperationValue.Sequence(values)
    fun strings(values: Collection<String>) = sequence(values.map(::v))
    fun box(value: WorkBox) = record("min" to v(value.min), "max" to v(value.max))
    fun area(value: WorkArea) = record("bounds" to box(value.bounds), "exclusions" to sequence(value.exclusions.map(::box)))
    fun containers(value: ContainerChoices?): OperationValue = if (value == null) OperationValue.Absent else
        record("positions" to sequence(value.positions.map(::v)), "preference" to v(value.preference.name))
    fun filter(value: NpcEntityTypeFilter) = record("typeIds" to strings(value.typeIds.sorted()), "tagIds" to strings(value.tagIds.sorted()))
    fun port(value: MachinePort) = record("endpoint" to record(
        "dimensionId" to v(value.endpoint.dimensionId), "position" to v(value.endpoint.position), "side" to v(value.endpoint.side?.name)),
        "slot" to v(value.slot), "itemId" to v(value.itemId), "quantity" to v(value.quantity))
    fun travel(anchor: NpcPosition, radius: Double, returnTo: NpcPosition?) =
        record("anchor" to v(anchor), "travelRadius" to v(radius), "returnTo" to v(returnTo))
    fun merged(vararg records: OperationValue.Record): OperationValue.Record {
        val fields = linkedMapOf<String, OperationValue>()
        for (record in records) for ((key, value) in record.fields) check(fields.put(key, value) == null) { "Duplicate inspection field" }
        return OperationValue.Record(fields)
    }
}
