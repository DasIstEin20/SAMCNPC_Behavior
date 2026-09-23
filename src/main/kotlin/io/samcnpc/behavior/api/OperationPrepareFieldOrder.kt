package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcPosition

/** Soil coordinates authorize hoe use only; no planting, irrigation or harvesting is implied. */
data class OperationPrepareFieldOrder(
    override val dimensionId: String,
    val area: OperationWorkArea,
    val anchor: NpcPosition,
    val travelRadius: Double = 32.0,
    val returnTo: NpcPosition? = null,
    override val budget: OperationBudget = OperationBudget(),
) : OperationOrder { override val type: OperationType get() = OperationType.FIELD_PREPARATION }
