package io.samcnpc.behavior.kernel.inventory

import io.samcnpc.core.api.*

internal enum class ContainerTransferDirection { WITHDRAW, DEPOSIT }
internal enum class ContainerTransferProblem { UNAVAILABLE, SOURCE_EMPTY, INVENTORY_FULL, STORAGE_FULL, REJECTED, UNCERTAIN, RESERVED }

internal data class ContainerTransferObservation(
    val position: NpcBlockPosition,
    val itemId: String,
    val direction: ContainerTransferDirection,
    val requested: Int,
    val npcBefore: Int,
    val npcAfter: Int,
    val containerBefore: Int,
    val containerAfter: Int,
    val containerSize: Int,
    val blockId: String,
) {
    val moved: Int get() = if (direction == ContainerTransferDirection.WITHDRAW) npcAfter - npcBefore else npcBefore - npcAfter
    val partial: Boolean get() = moved < requested
    fun valid(): Boolean = requested in 1..2304 && moved in 1..requested && containerSize in 1..64 &&
        listOf(npcBefore, npcAfter, containerBefore, containerAfter).all { it in 0..1_000_000 } &&
        npcAfter - npcBefore == containerBefore - containerAfter
}

internal data class ContainerTransferStep(
    val action: NpcActionResult,
    val observation: ContainerTransferObservation? = null,
    val problem: ContainerTransferProblem? = null,
)

/** One physical Core call, with fresh observations. No historical chest contents are assumed exclusive. */
internal object ContainerTransferKernel {
    fun transfer(npc: NpcFacade, world: NpcWorldView, position: NpcBlockPosition, itemId: String, count: Int,
                 direction: ContainerTransferDirection, inventorySlot: Int? = null, containerSlot: Int? = null): ContainerTransferStep {
        if (count !in 1..2304) return failure(ContainerTransferProblem.REJECTED, "transfer quantity must be 1..2304")
        val before = world.observeBlockContainer(position)
            ?: return failure(ContainerTransferProblem.UNAVAILABLE, "authorized container is unavailable")
        val block = world.observeBlock(position)
            ?: return failure(ContainerTransferProblem.UNAVAILABLE, "authorized container block is unobservable")
        if (before.isTruncated || before.containerSize !in 1..64) return failure(ContainerTransferProblem.UNAVAILABLE, "container exceeds the bounded observation")
        val inventory = npc.inventoryContents()
        val npcBefore = inventory.sumOf { if (it.stack.itemId == itemId) it.stack.count else 0 }
        val containerBefore = count(before, itemId)
        val result: NpcActionResult
        if (direction == ContainerTransferDirection.WITHDRAW) {
            val source = before.slots.firstOrNull { (containerSlot == null || it.slot == containerSlot) && it.stack.itemId == itemId && it.stack.count > 0 }
                ?: return failure(ContainerTransferProblem.SOURCE_EMPTY, "authorized source has no matching stack")
            if (inventory.none { it.stack.isEmpty || it.stack.itemId == itemId && it.stack.count < it.stack.maxStackSize }) {
                return failure(ContainerTransferProblem.INVENTORY_FULL, "NPC inventory has no candidate capacity")
            }
            result = npc.moveBlockContainerToInventory(NpcBlockContainerSlot(position, source.slot), minOf(count, source.stack.count))
        } else {
            val source = inventory.firstOrNull { (inventorySlot == null || it.slot == inventorySlot) && it.stack.itemId == itemId && it.stack.count > 0 }
                ?: return failure(ContainerTransferProblem.SOURCE_EMPTY, "NPC has no matching carried source stack")
            val destination = if (containerSlot != null) before.slots.firstOrNull { it.slot == containerSlot }
                else before.slots.firstOrNull { it.stack.isEmpty } ?: before.slots.firstOrNull { it.stack.itemId == itemId && it.stack.count < it.stack.maxStackSize }
            if (destination == null) return failure(ContainerTransferProblem.STORAGE_FULL, "authorized destination has no candidate slot")
            result = npc.moveInventoryToBlockContainer(source.slot, NpcBlockContainerSlot(position, destination.slot), minOf(count, source.stack.count))
        }
        val after = world.observeBlockContainer(position)
        val afterBlock = world.observeBlock(position)
        if (after == null || after.isTruncated || after.containerSize != before.containerSize || afterBlock?.blockId != block.blockId) {
            return failure(ContainerTransferProblem.UNCERTAIN, "container became unavailable or changed shape/type during transfer")
        }
        val npcAfter = npc.inventoryContents().sumOf { if (it.stack.itemId == itemId) it.stack.count else 0 }
        val containerAfter = count(after, itemId)
        if (result.status != NpcActionStatus.SUCCEEDED) {
            if (npcBefore != npcAfter || containerBefore != containerAfter) return failure(ContainerTransferProblem.UNCERTAIN, "rejected transfer changed inventory/container counts")
            return ContainerTransferStep(result, problem = ContainerTransferProblem.REJECTED)
        }
        val observation = ContainerTransferObservation(position, itemId, direction, count, npcBefore, npcAfter, containerBefore, containerAfter, after.containerSize, block.blockId)
        if (!observation.valid()) return failure(ContainerTransferProblem.UNCERTAIN, "transfer did not produce matching bounded inventory/container deltas")
        return ContainerTransferStep(result, observation)
    }

    fun count(container: NpcBlockContainerObservation, itemId: String): Int = container.slots.sumOf { if (it.stack.itemId == itemId) it.stack.count else 0 }
    private fun failure(problem: ContainerTransferProblem, detail: String) = ContainerTransferStep(
        if (problem == ContainerTransferProblem.UNCERTAIN) NpcActionResult.failed(detail) else NpcActionResult.rejected(detail), problem = problem)
}
