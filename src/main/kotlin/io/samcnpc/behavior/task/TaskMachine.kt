package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.navigation.ContainerApproachKernel
import io.samcnpc.core.api.*

/** Scheduling and bounded intent only; the selected machine alone owns its processing. */
internal object TaskMachine {
    fun capture(npc: NpcFacade,d: MachineTaskDefinition): MachineTaskState? {
        if (npc.containerTransferState() != null) return null
        val inventory = HarvestResources.inventoryCounts(npc)
        if (!HarvestResources.validCounts(inventory)) return null
        val needed = d.feeds.ports.groupBy { it.itemId }.mapValues { (_,ports) -> ports.sumOf { it.quantity } }
        if (needed.any { (id,count) -> npc.inventoryContents().sumOf { if (it.stack.itemId == id) it.stack.count else 0 } < count }) return null
        val world = npc.worldView()
        val shapes = ArrayList<MachinePortShape>(d.ports.size)
        for (port in d.ports) {
            val observed = world.observeContainer(port.endpoint) ?: return null
            if (port.slot !in observed.slots.indices) return null
            shapes.add(MachinePortShape(observed.blockId,observed.slotCount))
        }
        return MachineTaskState(HarvestResources.capture(npc),IntArray(d.feeds.ports.size),shapes)
    }
    fun observeInventory(record: TaskRecord,npc: NpcFacade): String? {
        val state = record.primary.machine ?: return null
        val current = HarvestResources.inventoryCounts(npc)
        return state.resources.reconcileLoad(npc.inventoryLoadSnapshot(),current) ?: state.resources.observeLive(current)
    }
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView): NpcActionResult {
        val d = record.active.definition as MachineTaskDefinition
        val s = checkNotNull(record.active.machine)
        val snapshot = npc.snapshot()
        record.reconciledPosition = snapshot.position
        if (world.dimensionId != d.dimensionId || snapshot.dimensionId != d.dimensionId) return fail(record,TaskReason.DIMENSION_CHANGED,"machine task left its assigned dimension")
        if (!d.contains(snapshot.position)) return fail(record,TaskReason.WORK_FAILED,"machine task left its fixed travel boundary; previous transfers retained")
        val problem = observeInventory(record,npc) ?: s.validationProblem(d)
        if (problem != null || s.resources.uncertain) return fail(record,TaskReason.STATE_MISMATCH,problem ?: "machine resource checkpoint is uncertain")
        if (npc.containerTransferState() != null) {
            s.resources.uncertain = true
            return fail(record,TaskReason.STATE_MISMATCH,"Core has an unconfirmed container transfer; no machine operation replayed")
        }
        if (s.goal(d)) return finish(record,e,npc,world,d,s)
        val block = d.output.endpoint.position
        if (TaskNavigator.distanceSquared(snapshot.position,TransportTaskDefinition.center(block)) > 9.0 || !snapshot.onGround) {
            s.phase = MachinePhase.APPROACH
            if (e.approach == null && !ContainerApproachKernel.admitSelection(world)) return NpcActionResult.running("machine approach queued in the shared planning budget")
            val approach = e.approach ?: ContainerApproachKernel.select(world,block,snapshot.position,allowed = {
                d.contains(NpcPosition(it.x+0.5,it.y.toDouble(),it.z+0.5))
            }) ?: return fail(record,TaskReason.WORK_FAILED,"machine has no supported approach inside the permitted area")
            e.approach = approach
            return TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,approach,budget=d.budget))
        }
        if (s.phase != MachinePhase.WORK) { TaskInventory.resetRoute(e,npc);s.phase=MachinePhase.WORK }
        if (s.pollRemaining > 0) return NpcActionResult.running("waiting for the next bounded machine observation")
        s.pollRemaining = d.pollTicks
        val observations = linkedMapOf<NpcContainerEndpoint,NpcContainerObservation>()
        val slots = ArrayList<NpcItemStackSnapshot>(d.ports.size)
        for ((index,port) in d.ports.withIndex()) {
            val observed = observations[port.endpoint] ?: world.observeContainer(port.endpoint)
                ?: return fail(record,TaskReason.WORK_FAILED,"machine endpoint disappeared or became unavailable: ${port.endpoint}")
            observations[port.endpoint] = observed
            val shape = s.shapes[index]
            if (observed.blockId != shape.blockId || observed.slotCount != shape.slots || port.slot !in observed.slots.indices)
                return fail(record,TaskReason.WORK_FAILED,"machine type or exposed slot shape changed; no confirmed feed repeated")
            slots.add(observed.slots[port.slot].stack)
        }
        val prior = s.observedSlots
        if (prior != null && prior != slots) s.idleTicks = 0
        s.observedSlots = java.util.List.copyOf(slots)
        if (s.idleTicks >= d.noProgressTicks) return fail(record,TaskReason.WORK_FAILED,
            "machine made no observed progress for ${s.idleTicks} active ticks; supplied=${s.supplied.toList()}, collected=${s.collected}/${d.output.quantity}; observed=$slots")
        for (offset in d.ports.indices) {
            val index = (s.cursor+offset)%d.ports.size
            val port = d.ports[index]
            val extracting = index == d.feeds.ports.size
            val remaining = port.quantity - if (extracting) s.collected else s.supplied[index]
            if (remaining == 0) continue
            val slot = if (extracting) null else npc.inventoryContents().firstOrNull { it.stack.itemId == port.itemId && it.stack.count > 0 }
            if (extracting && (slots[index].itemId != port.itemId || slots[index].count == 0)) continue
            if (!extracting && slot == null) return fail(record,TaskReason.MISSING_RESOURCE,"remaining machine feed ${port.itemId} is no longer carried; previous effects retained")
            val amount = minOf(64,remaining,if (extracting) slots[index].count else checkNotNull(slot).stack.count)
            val request = NpcContainerTransferRequest(port.endpoint,port.slot,port.itemId,amount)
            s.cursor = (index+1)%d.ports.size
            val result = if (extracting) npc.transferFromContainer(request) else npc.transferToContainer(checkNotNull(slot).slot,request)
            if (result.uncertain) {
                s.resources.uncertain = true
                return fail(record,TaskReason.STATE_MISMATCH,"unconfirmed machine transfer: ${result.action.detail}")
            }
            if (result.movedCount > 0) {
                val mismatch = s.resources.observeTransfer(HarvestResources.inventoryCounts(npc),port.itemId,result.movedCount,!extracting)
                if (mismatch != null) return fail(record,TaskReason.STATE_MISMATCH,mismatch)
                if (extracting) s.collected += result.movedCount else s.supplied[index] += result.movedCount
                s.transfers++;s.idleTicks=0
                val invalid = s.validationProblem(d)
                if (invalid != null) { s.resources.uncertain=true;return fail(record,TaskReason.STATE_MISMATCH,invalid) }
                record.detail="machine ${if (extracting) "output" else "feed"} moved=${result.movedCount}/$amount; supplied=${s.supplied.toList()}; collected=${s.collected}/${d.output.quantity}"
            } else {
                if (result.action.status == NpcActionStatus.UNSUPPORTED) return fail(record,TaskReason.WORK_FAILED,result.action.detail)
                record.detail="machine port made no transfer: ${result.action.code}: ${result.action.detail}".take(TaskRecord.MAX_DETAIL_LENGTH)
            }
            return NpcActionResult.running(record.detail)
        }
        record.detail="machine waiting for expected output; supplied=${s.supplied.toList()}, collected=${s.collected}/${d.output.quantity}; observed=$slots".take(TaskRecord.MAX_DETAIL_LENGTH)
        return NpcActionResult.running(record.detail)
    }
    private fun finish(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: MachineTaskDefinition,s: MachineTaskState): NpcActionResult {
        if (s.phase != MachinePhase.RETURN) { TaskInventory.resetRoute(e,npc);s.phase=MachinePhase.RETURN }
        val destination = d.returnTo
        if (destination != null) {
            val moved = TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,destination,budget=d.budget))
            if (moved.status != NpcActionStatus.SUCCEEDED) return moved
        }
        s.phase = MachinePhase.DONE
        record.completeActive(TaskReason.MACHINE_FINISHED,"machine feeds and actual output quota confirmed; transfers=${s.transfers}; collected=${s.collected}")
        return NpcActionResult.succeeded(record.detail)
    }
    private fun fail(record: TaskRecord,reason: TaskReason,detail: String): NpcActionResult {
        record.finish(TaskStatus.FAILED,reason,detail.take(TaskRecord.MAX_DETAIL_LENGTH))
        return NpcActionResult.failed(record.detail)
    }
}
