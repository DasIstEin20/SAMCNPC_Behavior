package io.samcnpc.behavior.operation

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.registry.BoundedBehaviorJson
import io.samcnpc.behavior.task.TaskChanges
import io.samcnpc.behavior.task.TaskPublicAmendments
import io.samcnpc.core.api.NpcActionStatus

internal object OperationDocuments {
    fun order(json: String): OperationDocumentResult<OperationOrder> = decode(json, "orderDocument") { document ->
        val order = OperationOrderReader.read(document)
        val validation = OperationSupervisionApi.validateOrder(order)
        require(validation.status == NpcActionStatus.SUCCEEDED) { validation.detail }
        order
    }

    fun change(json: String): OperationDocumentResult<OperationChange> = decode(json, "changeDocument") { document ->
        val p = document.obj("parameters")
        val value: OperationChange = when (document.text("type")) {
            "QUANTITY" -> OperationChange.Quantity(p.int("amount"), OperationQuantityMode.valueOf(p.text("mode")))
            "RECIPIENTS" -> OperationChange.Recipients(OperationValueReader.containers(p.obj("containers")))
            "SOURCES" -> OperationChange.Sources(p.maybe("containers")?.let(OperationValueReader::containers))
            "EXTEND_TIME" -> OperationChange.ExtendTime(p.int("ticks"))
            "REPLACE" -> OperationChange.Replace(OperationOrderReader.read(p.obj("order")), OperationObjectiveMode.valueOf(p.text("objective")))
            "TACTICS" -> OperationChange.Tactics(OperationValueReader.tactics(p.obj("tactics")))
            "REACTION" -> OperationChange.Reaction(OperationValueReader.reaction(p.obj("policy")))
            "LOGISTICS" -> OperationChange.Logistics(OperationValueReader.logistics(p.obj("policy")))
            else -> error("Validated change discriminator is not mapped")
        }
        val problem = TaskChanges.validationProblem(TaskPublicAmendments.change(value))
        require(problem == null) { problem.orEmpty() }
        value
    }

    private fun <T> decode(json: String, shape: String, convert: (JsonObject) -> T): OperationDocumentResult<T> {
        if (json.length > OperationDocumentApi.MAX_DOCUMENT_BYTES) return OperationDocumentResult.Rejected("TOO_LARGE", "Operation document exceeds 64 KiB")
        return try {
            // Reuse the disk-pack lexical boundary: duplicates, malformed Unicode, depth and numeric size are rejected.
            val value = BoundedBehaviorJson.parse(json)
            if (json.toByteArray(Charsets.UTF_8).size > OperationDocumentApi.MAX_DOCUMENT_BYTES)
                return OperationDocumentResult.Rejected("TOO_LARGE", "Operation document exceeds 64 KiB UTF-8")
            val normalized = OperationJsonShape.normalize(value, ref(shape)).asJsonObject
            OperationDocumentResult.Accepted(convert(normalized))
        } catch (error: JsonParseException) {
            OperationDocumentResult.Rejected("INVALID_JSON", error.message.orEmpty().take(512))
        } catch (error: IllegalArgumentException) {
            OperationDocumentResult.Rejected("INVALID_DEFINITION", error.message.orEmpty().take(512))
        }
    }
}
