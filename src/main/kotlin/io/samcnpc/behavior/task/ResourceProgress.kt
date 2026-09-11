package io.samcnpc.behavior.task

/** Counts are facts, never item stacks or permission to reconstruct a missing world effect. */
internal data class TransferReceipt(
    val sequence: Int,
    val npcBefore: Int,
    val npcAfter: Int,
    val containerBefore: Int,
    val containerAfter: Int,
) {
    val amount: Int get() = npcBefore - npcAfter
    fun valid(): Boolean = sequence in 1..2304 && amount > 0 &&
        listOf(npcBefore, npcAfter, containerBefore, containerAfter).all { it in 0..ResourceProgress.MAX_COUNT } &&
        containerAfter - containerBefore == amount
}

internal class ResourceProgress(
    val initial: Int,
    var retained: Int,
    var delivered: Int,
    var containerCount: Int,
    val containerSize: Int,
    var receipt: TransferReceipt? = null,
    var observedRetained: Int? = null,
    var uncertain: Boolean = false,
) {
    /** Body-load observation already checked in this process; never serialized as proof. */
    var observedLoadGeneration: java.util.UUID? = null

    fun reconcileLoadedInventory(generation: java.util.UUID, loadedCount: Int): String? {
        if (generation == observedLoadGeneration) return null
        if (loadedCount != retained) {
            uncertain = true
            return "loaded NPC stock $loadedCount differs from confirmed $retained before new pickup or transfer; no replay"
        }
        observedLoadGeneration = generation
        return null
    }

    fun validate(quantity: Int): Boolean = initial in 0..MAX_COUNT && retained in 0..initial &&
        delivered in 0..quantity && initial == retained + delivered && containerCount in 0..MAX_COUNT &&
        containerSize in 1..64 && (observedRetained == null || observedRetained in 0..MAX_COUNT) &&
        (receipt?.let { it.valid() && it.npcAfter == retained && it.containerAfter == containerCount && it.amount <= delivered } ?: (delivered == 0))

    fun reconcile(npcCount: Int, targetCount: Int, targetSize: Int): String? {
        observedRetained = npcCount
        if (npcCount == retained && targetCount == containerCount && targetSize == containerSize) return null
        uncertain = true
        return "resource checkpoint differs: NPC $npcCount/$retained, container $targetCount/$containerCount, size $targetSize/$containerSize; confirmed delivery retained, no replay"
    }

    fun confirm(npcAfter: Int, containerAfter: Int, requested: Int): Boolean {
        observedRetained = npcAfter
        val next = TransferReceipt((receipt?.sequence ?: 0) + 1, retained, npcAfter, containerCount, containerAfter)
        if (!next.valid() || next.amount > requested) { uncertain = true; return false }
        retained = npcAfter
        containerCount = containerAfter
        delivered += next.amount
        receipt = next
        return true
    }

    fun describe(quantity: Int): String = "initial=$initial; gathered=0; consumed=0; retained=${observedRetained ?: retained}; " +
        "confirmedRetained=$retained; delivered=$delivered; shortage=${(quantity - delivered).coerceAtLeast(0)}; uncertain=$uncertain"

    companion object { const val MAX_COUNT = 1_000_000 }
}
