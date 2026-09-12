package io.samcnpc.behavior.task

internal enum class FishingPhase { APPROACH, CAST, WAIT, COLLECT, RETURN, DONE }

internal class FishingTaskState(
    val resources: HarvestResources,
    var phase: FishingPhase = FishingPhase.APPROACH,
    var casts: Int = 0,
    var caught: Int = 0,
    var collectTicks: Int = 0,
    var collectedCatches: Int = 0,
    var pendingReel: Boolean = false,
    val spawned: MutableMap<String, Int> = linkedMapOf(),
    val drops: MutableMap<String, Int> = linkedMapOf(),
    val collectionBaseline: MutableMap<String, Int> = linkedMapOf(),
) {
    fun validationProblem(d: FishingTaskDefinition): String? = when {
        casts !in 0..d.maximumCasts || caught !in 0..d.catches || caught > casts -> "invalid fishing cast/catch counters"
        collectedCatches !in 0..caught || caught - collectedCatches != if (phase == FishingPhase.COLLECT) 1 else 0 -> "fishing catch collection history is inconsistent"
        !HarvestResources.validCounts(spawned) || spawned.values.sum().toLong() !in caught.toLong()..caught.toLong()*4096 -> "fishing catch count lacks bounded actual drop receipts"
        collectedCatches > 0 && spawned.any { (id, count) -> (resources.entries[id]?.gathered ?: 0) < count - (drops[id] ?: 0) } -> "fishing collected receipt lacks gross inventory pickup evidence"
        collectTicks !in 0..d.pickupWaitTicks -> "invalid fishing collection clock"
        drops.size > 64 || drops.values.any { it !in 1..4096 } || drops.values.sum() > 4096 -> "fishing drop obligation exceeds one bounded catch"
        !HarvestResources.validCounts(drops) || !HarvestResources.validCounts(collectionBaseline.filterValues { it > 0 }) -> "invalid fishing item counts"
        collectionBaseline.keys != drops.keys || collectionBaseline.values.any { it !in 0..HarvestResource.MAX_COUNT } -> "fishing collection baseline does not match drop identities"
        phase == FishingPhase.COLLECT && (drops.isEmpty() || caught == 0) -> "fishing collection has no confirmed catch"
        phase != FishingPhase.COLLECT && drops.isNotEmpty() -> "unexpected pending fishing drops"
        pendingReel && phase != FishingPhase.WAIT -> "fishing payout fence outside a waiting cast"
        phase == FishingPhase.DONE && caught != d.catches -> "fishing completion lacks its quota"
        else -> null
    }
}
