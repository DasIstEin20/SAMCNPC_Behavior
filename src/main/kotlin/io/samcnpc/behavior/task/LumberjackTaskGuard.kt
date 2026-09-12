package io.samcnpc.behavior.task

import io.samcnpc.behavior.lumberjack.tree.isLumberjackLeafBlock
import io.samcnpc.behavior.lumberjack.tree.isLumberjackSnowLayer
import io.samcnpc.core.api.*
import io.samcnpc.behavior.kernel.inventory.*

/** Final policy fence around real Core calls. World observations are never altered. */
internal class LumberjackTaskGuard(
    private val delegate: NpcFacade,
    private val world: NpcWorldView,
    private val definition: LumberjackTaskDefinition,
    private val state: LumberjackTaskState,
) : NpcFacade by delegate {
    var problem: String? = null
        private set
    var placedBlock: Boolean = false
        private set

    override fun startBlockBreak(position: NpcBlockPosition): NpcActionResult {
        val denial = breakProblem(position)
        if (denial != null) return deny(denial)
        val observed = world.observeBlock(position) ?: return deny("work block became unavailable")
        val result = delegate.startBlockBreak(position)
        if (result.status == NpcActionStatus.ACCEPTED || result.status == NpcActionStatus.RUNNING || result.status == NpcActionStatus.SUCCEEDED) {
            state.pendingBreak = WorkBlockCheckpoint(position, observed.blockId)
            state.pendingActionId = result.actionId
        }
        return result
    }

    override fun continueBlockBreak(): NpcActionResult {
        val pending = state.pendingBreak ?: return deny("no observed work identity for continued break")
        val observed = world.observeBlock(pending.position)
        if (observed?.isAir != true) {
            val denial = breakProblem(pending.position)
            if (denial != null || observed?.blockId != pending.blockId) {
                delegate.abortBlockBreak()
                return deny(denial ?: "work block identity changed during break")
            }
        }
        return delegate.continueBlockBreak()
    }

    override fun placeHeldBlock(placement: NpcBlockPlacement, hand: NpcHand): NpcActionResult {
        if (!definition.area.contains(placement.position)) return deny("support placement is outside the permitted work area")
        val result = delegate.placeHeldBlock(placement, hand)
        if (result.status == NpcActionStatus.SUCCEEDED) placedBlock = true
        return result
    }

    override fun moveInventoryToBlockContainer(inventorySlot: Int, destination: NpcBlockContainerSlot, count: Int): NpcActionResult {
        if (destination.position != definition.destination) return deny("delivery to an unassigned container")
        val item = delegate.inventoryContents().firstOrNull { it.slot == inventorySlot }?.stack?.itemId ?: return deny("missing actual delivery source")
        return checkedTransfer(ContainerStepReservations.transfer(state.job.workTaskId, delegate, world, destination.position, item, count,
            ContainerTransferDirection.DEPOSIT, inventorySlot, destination.slot))
    }

    override fun moveBlockContainerToInventory(source: NpcBlockContainerSlot, count: Int): NpcActionResult {
        if (definition.version == 1 && source.position != definition.destination || definition.version >= 2 && source.position !in definition.supplySources?.positions.orEmpty()) return deny("supply from an unassigned container")
        val item = world.observeBlockContainer(source.position)?.slots?.firstOrNull { it.slot == source.slot }?.stack?.itemId ?: return deny("missing actual supply source")
        return checkedTransfer(ContainerStepReservations.transfer(state.job.workTaskId, delegate, world, source.position, item, count,
            ContainerTransferDirection.WITHDRAW, containerSlot = source.slot))
    }

    private fun checkedTransfer(step: ContainerTransferStep): NpcActionResult {
        if (step.problem == ContainerTransferProblem.UNCERTAIN) problem = step.action.detail
        return step.action
    }

    override fun useItemOnBlock(hit: NpcBlockHit, hand: NpcHand): NpcActionResult = deny("unclassified block use is not part of this operation")
    override fun useInteractiveBlock(position: NpcBlockPosition): NpcActionResult = deny("unclassified block interaction is not part of this operation")

    private fun breakProblem(position: NpcBlockPosition): String? {
        if (!definition.area.contains(position)) return "block break is outside the permitted work area"
        val observed = world.observeBlock(position) ?: return "work block is unavailable"
        val supportId = state.job.pillarSession?.placedBlockIds?.get(position)
        if (supportId != null) return if (supportId == observed.blockId) null else "recorded support was replaced"
        if (position in state.observedRemovedBlocks) return "previously removed work block was replaced; no replay authorized"
        if (state.observedRemovedBlocks.size >= LumberjackTaskState.MAX_REMOVED_BLOCKS) return "removed-block journal is full"
        return if (definition.wood.matches(observed.blockId) || observed.isLumberjackLeafBlock() || observed.isLumberjackSnowLayer()) null
        else "block is outside the selected wood/foliage policy: ${observed.blockId}"
    }

    private fun deny(detail: String): NpcActionResult {
        problem = detail
        return NpcActionResult.rejected(detail, NpcActionCode.WORLD_REJECTED)
    }
}
