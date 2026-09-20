package io.samcnpc.behavior.operation

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.task.TaskReason

internal object RegisteredOperationCatalog {
    val snapshot: OperationCatalog by lazy {
        val operations = (BasicOperationDescriptors.values() + CombatOperationDescriptors.values() +
            HarvestOperationDescriptors.values()).sortedBy { it.type.ordinal }
        check(operations.map { it.type } == OperationType.entries.toList())
        val shapes = LinkedHashMap<String, OperationInput>()
        shapes.putAll(CommonOperationShapes.values())
        shapes.putAll(HarvestOperationShapes.values())
        shapes.putAll(OperationChangeShapes.shapes())
        shapes["plantingParameters"] = operations.single { it.type == OperationType.PLANTING }.parameters
        for (entry in operations) {
            shapes["order_" + entry.type.name] = record(
                required("documentVersion", integer(1, 1, "version")),
                required("type", choice(entry.type.operationId)),
                required("definitionVersion", integer(entry.type.definitionVersion, entry.type.definitionVersion, "version")),
                required("parameters", entry.parameters))
        }
        shapes["orderDocument"] = OperationInput.Alternatives(operations.map { "order_" + it.type.name })
        val changes = OperationChangeShapes.changes()
        for ((name, type) in changes) shapes["change_" + name] = OperationSchemaExport.changeEnvelope(name, type)
        shapes["changeDocument"] = OperationInput.Alternatives(changes.keys.map { "change_" + it })
        OperationCatalog(1, 1, operations, shapes, changes,
            OperationTaskState.entries.map { it.name }, TaskReason.entries.map { it.name })
    }
}
