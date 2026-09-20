package io.samcnpc.behavior.api

import io.samcnpc.behavior.operation.OperationDocuments
import io.samcnpc.behavior.operation.OperationSchemaExport

/** Definition documents contain no actor, NPC identity, task revisions or authority. */
object OperationDocumentApi {
    const val MAX_DOCUMENT_BYTES: Int = 64 * 1024
    fun catalogJson(): String = OperationSchemaExport.catalogJson
    fun orderSchema(): String = OperationSchemaExport.orderSchema
    fun changeSchema(): String = OperationSchemaExport.changeSchema
    fun decodeOrder(json: String): OperationDocumentResult<OperationOrder> = OperationDocuments.order(json)
    fun decodeChange(json: String): OperationDocumentResult<OperationChange> = OperationDocuments.change(json)
}

sealed interface OperationDocumentResult<out T> {
    data class Accepted<T>(val value: T) : OperationDocumentResult<T>
    data class Rejected(val code: String, val detail: String) : OperationDocumentResult<Nothing>
}
