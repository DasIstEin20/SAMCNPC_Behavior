package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.ContainerTransferDirection
import io.samcnpc.behavior.kernel.inventory.ContainerTransferObservation
import io.samcnpc.core.api.NpcBlockPosition
import java.util.UUID

/** Only measured withdrawal creates cargo. Initial stock and unrelated pickup stay protected. */
internal class TransportLedger(
    val itemId: String,
    val initial: Int,
    var retained: Int = initial,
    var withdrawn: Int = 0,
    var delivered: Int = 0,
    var incidentalGained: Int = 0,
    var incidentalLost: Int = 0,
    var cargoLost: Int = 0,
    val withdrawals: MutableMap<NpcBlockPosition, Int> = linkedMapOf(),
    val deliveries: MutableMap<NpcBlockPosition, Int> = linkedMapOf(),
    var transferCount: Int = 0,
    var lastTransfer: ContainerTransferObservation? = null,
    var uncertain: Boolean = false,
    val initialCargo: Int = 0,
    val legacyCredit: LegacyDeliveryCredit? = null,
) {
    var observedLoadGeneration: UUID? = null
    var mustReconcileLoad = true
    val cargo: Int get() = initialCargo + withdrawn - delivered - cargoLost
    val protected: Int get() = retained - cargo
    fun deliverable(reserve: Int): Int = minOf(cargo, (retained - reserve).coerceAtLeast(0))
    fun needed(quantity: Int, reserve: Int): Int = (quantity - delivered + (reserve - protected).coerceAtLeast(0) - cargo).coerceAtLeast(0)
    fun valid(): Boolean = initialCargo in 0..initial && (legacyCredit?.valid(initial) != false) &&
        (legacyCredit == null || initialCargo == initial && (deliveries[legacyCredit.destination] ?: 0) >= legacyCredit.delivered) && listOf(initial, retained, withdrawn, delivered, incidentalGained, incidentalLost, cargoLost).all { it in 0..MAX_COUNT } &&
        initial.toLong() + withdrawn + incidentalGained == retained.toLong() + delivered + incidentalLost + cargoLost && cargo in 0..retained &&
        withdrawals.size <= MAX_ENDPOINTS && deliveries.size <= MAX_ENDPOINTS && withdrawals.values.all { it in 1..MAX_COUNT } && deliveries.values.all { it in 1..MAX_COUNT } &&
        withdrawals.values.sumOf { it.toLong() } == withdrawn.toLong() && deliveries.values.sumOf { it.toLong() } == delivered.toLong() && transferCount in 0..MAX_TRANSFERS &&
        (lastTransfer?.let { it.valid() && it.itemId == itemId && transferCount > 0 } ?: (transferCount == 0 && withdrawn == 0 && delivered == (legacyCredit?.delivered ?: 0)))

    fun reconcileLoad(generation: UUID?, countAtLoad: Int): String? {
        if (!mustReconcileLoad && (generation == null || generation == observedLoadGeneration)) return null
        if (countAtLoad != retained) { uncertain = true; return "loaded cargo inventory differs from the checkpoint; no transfer replayed" }
        observedLoadGeneration = generation; mustReconcileLoad = false
        return null
    }
    fun observeLive(count: Int): String? {
        if (uncertain || !valid()) return "cargo ledger is uncertain; no checkpoint overwritten"
        if (count !in 0..MAX_COUNT) { uncertain = true; return "cargo inventory exceeds bounded accounting" }
        val delta = count - retained
        val gained = incidentalGained.toLong() + delta.coerceAtLeast(0)
        val lostCargo = if (delta < 0) minOf(-delta, cargo) else 0
        val cargoLoss = cargoLost.toLong() + lostCargo
        val otherLoss = incidentalLost.toLong() + (-delta).coerceAtLeast(0) - lostCargo
        if (listOf(gained, cargoLoss, otherLoss).any { it !in 0L..MAX_COUNT.toLong() }) {
            uncertain = true; return "cargo accounting limit reached; last valid checkpoint retained"
        }
        incidentalGained = gained.toInt(); cargoLost = cargoLoss.toInt(); incidentalLost = otherLoss.toInt(); retained = count
        return null
    }
    fun confirm(observation: ContainerTransferObservation): Boolean {
        if (uncertain || !valid() || !observation.valid() || observation.itemId != itemId || observation.npcBefore != retained || transferCount >= MAX_TRANSFERS) { uncertain = true; return false }
        val rows = if (observation.direction == ContainerTransferDirection.WITHDRAW) withdrawals else deliveries
        if (observation.position !in rows && rows.size >= MAX_ENDPOINTS || observation.direction == ContainerTransferDirection.DEPOSIT && observation.moved > cargo) { uncertain = true; return false }
        val nextRow = (rows[observation.position] ?: 0).toLong() + observation.moved
        val nextTotal = (if (observation.direction == ContainerTransferDirection.WITHDRAW) withdrawn else delivered).toLong() + observation.moved
        if (nextRow > MAX_COUNT || nextTotal > MAX_COUNT || observation.npcAfter !in 0..MAX_COUNT) { uncertain = true; return false }
        rows[observation.position] = nextRow.toInt()
        if (observation.direction == ContainerTransferDirection.WITHDRAW) withdrawn += observation.moved else delivered += observation.moved
        retained = observation.npcAfter; transferCount++; lastTransfer = observation
        if (!valid()) { uncertain = true; return false }
        return true
    }
    fun describe(quantity: Int): String = "item=$itemId; initial=$initial; withdrawn=$withdrawn; delivered=$delivered; cargo=$cargo; retained=$retained; protected=$protected; " +
        "incidentalPickup=$incidentalGained; incidentalLoss=$incidentalLost; cargoLoss=$cargoLost; shortage=${(quantity - delivered).coerceAtLeast(0)}; excess=${(delivered - quantity).coerceAtLeast(0)}; transfers=$transferCount; uncertain=$uncertain"

    companion object { const val MAX_COUNT = 1_000_000; const val MAX_TRANSFERS = 4096; const val MAX_ENDPOINTS = 32 }
}

internal enum class TransportPhase { SOURCE, DESTINATION, RETURN }
internal data class ContainerCheckpoint(val blockId: String, val size: Int)
internal class TransportTaskState(
    val ledger: TransportLedger,
    var phase: TransportPhase = TransportPhase.SOURCE,
    var selected: NpcBlockPosition? = null,
    val checkpoints: MutableMap<NpcBlockPosition, ContainerCheckpoint> = linkedMapOf(),
    var lastProblem: io.samcnpc.behavior.kernel.inventory.ContainerTransferProblem? = null,
) {
    /** A failed candidate is deferred only within this pass; a real retry clears it. */
    val deferred = mutableSetOf<NpcBlockPosition>()
}

/** Published carried-delivery facts retain their original receipt when upgraded to cargo accounting. */
internal data class LegacyDeliveryCredit(val destination: NpcBlockPosition, val delivered: Int, val receipt: TransferReceipt) {
    fun valid(initial: Int): Boolean = delivered in 1..initial && receipt.valid() &&
        receipt.npcAfter == initial - delivered && receipt.amount <= delivered
}
