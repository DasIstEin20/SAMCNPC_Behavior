package io.samcnpc.behavior.task

internal object FoodStatus {
    fun mode(work: FoodWorkOrder): String = when (work) {
        is FoodWorkOrder.Drops -> "drops"
        is FoodWorkOrder.Berries -> "berries"
        is FoodWorkOrder.Stored -> "stored"
        is FoodWorkOrder.Hunt -> "hunt"
    }
}
