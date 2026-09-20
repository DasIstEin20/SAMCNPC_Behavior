package io.samcnpc.behavior.api

/** Accounting provenance, not current container stock or permission to spend an item. */
enum class OperationResourceLedgerKind { PHYSICAL, PRODUCED, TRANSPORT, LEGACY_DELIVERY }

sealed interface OperationResourceInspection {
    data object NotTracked : OperationResourceInspection
    data object NotInitialized : OperationResourceInspection
    class Checkpoint internal constructor(
        val kind: OperationResourceLedgerKind,
        items: List<OperationItemAccounting>,
        val uncertain: Boolean,
        val reconciliationRequired: Boolean,
    ) : OperationResourceInspection {
        val items: List<OperationItemAccounting> = java.util.List.copyOf(items)
        init { require(this.items.size <= 64 && this.items.map { it.itemId }.distinct().size == this.items.size) }
    }
}

class OperationItemAccounting internal constructor(val itemId: String, counters: List<OperationMeasuredCount>) {
    val counters: List<OperationMeasuredCount> = java.util.List.copyOf(counters)
    init {
        require(itemId.length in 1..256 && this.counters.size <= 16)
        require(this.counters.map { it.name }.distinct().size == this.counters.size)
    }
}
