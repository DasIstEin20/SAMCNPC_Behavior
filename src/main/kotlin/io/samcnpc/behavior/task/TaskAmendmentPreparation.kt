package io.samcnpc.behavior.task

import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.lumberjack.model.LumberjackWorkSelection
import io.samcnpc.core.api.*

/** Builds and validates detached state; only the caller's final commit releases live controls. */
internal object TaskAmendmentPreparation {
    fun boundary(record: TaskRecord, change: TaskChange): Boolean {
        if (record.frames.any { it.inventory != null } && change !is TaskChange.ExtendTime && change !is TaskChange.Tactics && change !is TaskChange.Reaction) return false
        if(record.primary.planting != null && !policyOnly(change)) {
            val state=checkNotNull(record.primary.planting)
            if(record.frames.size != 1 || state.selected != null || state.phase !in setOf(PlantingPhase.SELECT,PlantingPhase.RETURN)) return false
        }
        if (record.primary.farming != null && !policyOnly(change)) {
            val state=checkNotNull(record.primary.farming)
            if (record.frames.size != 1 || state.target != null || state.phase !in setOf(FarmPhase.PREPARE,FarmPhase.SELECT,FarmPhase.WAIT_GROWTH,FarmPhase.DEPOSIT,FarmPhase.RETURN)) return false
        }
        if (record.primary.food != null && !policyOnly(change)) {
            if (record.frames.size != 1 || record.primary.food?.phase !in setOf(FoodPhase.SELECT,FoodPhase.WITHDRAW,FoodPhase.DEPOSIT,FoodPhase.RETURN)) return false
        }
        if (record.primary.mining != null && change !is TaskChange.ExtendTime && change !is TaskChange.Tactics && change !is TaskChange.Reaction) {
            if (record.frames.size != 1 || record.primary.mining?.phase !in setOf(MiningPhase.SELECT,MiningPhase.DEPOSIT,MiningPhase.RETURN)) return false
        }
        if (change is TaskChange.Quantity || change is TaskChange.ExtendTime || change is TaskChange.Tactics || change is TaskChange.Reaction) return true
        if (record.frames.size != 1) return false
        val wood = record.primary.lumberjack ?: return true
        return wood.pendingBreak == null && wood.job.pillarSession?.placedPositions.orEmpty().isEmpty() &&
            wood.job.phase in setOf(LumberjackDemoPhase.TRAVEL_TO_CHEST, LumberjackDemoPhase.PREPARE_EQUIPMENT,
                LumberjackDemoPhase.SEARCH_WOOD, LumberjackDemoPhase.RETURN_TO_CHEST, LumberjackDemoPhase.DEPOSIT_WOOD)
    }
    private fun policyOnly(change: TaskChange): Boolean = change is TaskChange.Tactics || change is TaskChange.ExtendTime || change is TaskChange.Reaction
    fun proposed(record: TaskRecord, request: TaskAmendmentRequest, snapshot: NpcSnapshot): TaskDefinition {
        val old = record.primary.definition
        require(old !is ExplorerTaskDefinition || policyOnly(request.change)) { "exploration bounds and visited-cell contract are fixed; cancel and assign another expedition to replace them" }
        require(old !is FishingTaskDefinition || policyOnly(request.change)) { "fishing pond/quota is fixed; cancel and assign another task to replace it" }
        require(old !is PrepareFieldTaskDefinition || policyOnly(request.change)) { "field geometry is fixed; cancel and assign another task before changing it" }
        require(old !is MachineTaskDefinition || policyOnly(request.change)) { "machine feed contract is fixed; cancel and assign another contract before changing ports or quantities" }
        var proposed = TaskChanges.proposed(old, request.change)
        if (!policyOnly(request.change) && proposed is DeliveryTaskDefinition && proposed.version == 1) proposed = proposed.copy(version = 2, anchor = snapshot.position)
        if (!policyOnly(request.change) && proposed is LumberjackTaskDefinition && proposed.version == 1) proposed = proposed.copy(version = 2,
            supplySources = ContainerChoices(listOf((old as LumberjackTaskDefinition).destination)))
        require(proposed.operationId == old.operationId) { "operation: changing operation kind requires a separately assigned task; current report/cleanup must be retained" }
        require(proposed.dimensionId == old.dimensionId && snapshot.dimensionId == old.dimensionId) { "dimension: amendment cannot move a task across worlds" }
        if (request.change !is TaskChange.ExtendTime) require(proposed.budget == old.budget) { "budget: use the explicit bounded time extension" }
        require(proposed.budget.attempts == old.budget.attempts && proposed.budget.backoffTicks == old.budget.backoffTicks) { "retry budget cannot be reset by amendment" }
        val problem = proposed.validationProblem(); require(problem == null) { problem.orEmpty() }
        val newObjective = (request.change as? TaskChange.Replace)?.objective == ObjectiveChangeMode.NEW_OBJECTIVE
        require(!newObjective || record.amendments.objectives.size < TaskAmendmentState.MAX_OBJECTIVES) { "objective history limit reached" }
        when {
            old is PlantingTaskDefinition && proposed is PlantingTaskDefinition -> require(newObjective || old.work.withSources(proposed.work.sources) == proposed.work) { "planting species/layout/field/reserve change requires NEW_OBJECTIVE" }
            old is FarmTaskDefinition && proposed is FarmTaskDefinition -> require(newObjective || old.work.copy(seedSources=proposed.work.seedSources) == proposed.work) { "farm crop/field/mode/cycle/reserve change requires NEW_OBJECTIVE" }
            old is FoodTaskDefinition && proposed is FoodTaskDefinition -> {
                val oldWork=old.work
                val nextWork=proposed.work
                val sameWork=oldWork == nextWork || oldWork is FoodWorkOrder.Stored && nextWork is FoodWorkOrder.Stored && oldWork.sourceKeep == nextWork.sourceKeep
                require(newObjective || old.outputs == proposed.outputs && sameWork) { "food resource/mode/area change requires NEW_OBJECTIVE" }
            }
            old is MiningTaskDefinition && proposed is MiningTaskDefinition -> require(newObjective || old.work == proposed.work && old.outputs == proposed.outputs && old.counting == proposed.counting) { "mining work/resource/counting change requires NEW_OBJECTIVE; quantity and recipient retain progress" }
            old is TransportTaskDefinition && proposed is TransportTaskDefinition -> require(newObjective || old.itemId == proposed.itemId) { "item: resource change requires NEW_OBJECTIVE" }
            old is DeliveryTaskDefinition && proposed is DeliveryTaskDefinition -> require(newObjective || old.itemId == proposed.itemId) { "item: resource change requires NEW_OBJECTIVE" }
            old is LumberjackTaskDefinition && proposed is LumberjackTaskDefinition -> require(newObjective || old.wood == proposed.wood && old.replant == proposed.replant) { "wood: resource filter/replant contract change requires NEW_OBJECTIVE" }
            old is AttackTaskDefinition && proposed is AttackTaskDefinition -> require(!newObjective && old.targetUuid == proposed.targetUuid) { "target: use a separately assigned combat objective; in-flight defeats cannot be retargeted" }
            old is InventoryTaskDefinition -> require(!newObjective && (request.change is TaskChange.ExtendTime || request.change is TaskChange.Tactics || request.change is TaskChange.Reaction)) { "inventory work: finish the captured bounded request before replacing its resources or route" }
            old is CombatMissionDefinition -> require(!newObjective && (request.change is TaskChange.Tactics || request.change is TaskChange.ExtendTime || request.change is TaskChange.Quantity && old is AreaAttackTaskDefinition)) { "mission: only tactics, explicit duration and existing defeat quota are amendable" }
        }
        if(!newObjective && old is LumberjackTaskDefinition && proposed is LumberjackTaskDefinition && record.primary.planting != null) {
            require(proposed.destination == old.destination && proposed.supplySources == old.supplySources && proposed.quantity <= checkNotNull(record.primary.lumberjack).resources.delivered(old.wood)) { "wood delivery is already complete; a different delivery route or additional wood requires NEW_OBJECTIVE" }
        }
        return proposed
    }
    fun prepare(record: TaskRecord, request: TaskAmendmentRequest, npc: NpcFacade, world: NpcWorldView): TaskRecord {
        val snapshot = npc.snapshot()
        val proposed = proposed(record, request, snapshot)
        val candidate = TaskCodec.read(TaskCodec.write(record))
        candidate.primary.fieldPreparation?.let { next ->
            val prior=checkNotNull(record.primary.fieldPreparation)
            next.resources.observedLoadGeneration=prior.resources.observedLoadGeneration
            next.resources.mustReconcileLoad=prior.resources.mustReconcileLoad
            next.reconcileWorld=prior.reconcileWorld;next.reconcileCursor=prior.reconcileCursor
        }
        candidate.primary.fishing?.resources?.let { next ->
            val prior=checkNotNull(record.primary.fishing).resources
            next.observedLoadGeneration=prior.observedLoadGeneration;next.mustReconcileLoad=prior.mustReconcileLoad
        }
        candidate.primary.machine?.let { next ->
            val prior=checkNotNull(record.primary.machine)
            next.resources.observedLoadGeneration=prior.resources.observedLoadGeneration
            next.resources.mustReconcileLoad=prior.resources.mustReconcileLoad
            next.observedSlots=prior.observedSlots
        }
        candidate.primary.planting?.let { next ->
            val old=checkNotNull(record.primary.planting)
            next.resources.observedLoadGeneration=old.resources.observedLoadGeneration; next.resources.mustReconcileLoad=old.resources.mustReconcileLoad
            next.reconcileWorld=old.reconcileWorld; next.reconcileCursor=old.reconcileCursor
        }
        candidate.primary.farming?.let { next ->
            val prior=checkNotNull(record.primary.farming)
            next.resources.physical.observedLoadGeneration=prior.resources.physical.observedLoadGeneration
            next.resources.physical.mustReconcileLoad=prior.resources.physical.mustReconcileLoad
            next.reconcileWorld=prior.reconcileWorld; next.reconcileCursor=prior.reconcileCursor
        }
        candidate.primary.food?.resources?.physical?.let { next ->
            val prior=checkNotNull(record.primary.food).resources.physical
            next.observedLoadGeneration=prior.observedLoadGeneration; next.mustReconcileLoad=prior.mustReconcileLoad
        }
        candidate.primary.mining?.let { next ->
            val prior = checkNotNull(record.primary.mining)
            next.resources.physical.observedLoadGeneration = prior.resources.physical.observedLoadGeneration
            next.resources.physical.mustReconcileLoad = prior.resources.physical.mustReconcileLoad
            next.reconcileWorld = prior.reconcileWorld; next.reconcileCursor = prior.reconcileCursor
        }
        candidate.primary.transport?.ledger?.let { next ->
            val prior = checkNotNull(record.primary.transport).ledger
            next.observedLoadGeneration = prior.observedLoadGeneration; next.mustReconcileLoad = prior.mustReconcileLoad
        }
        candidate.primary.lumberjack?.resources?.let { next ->
            val prior = checkNotNull(record.primary.lumberjack).resources
            next.observedLoadGeneration = prior.observedLoadGeneration; next.mustReconcileLoad = prior.mustReconcileLoad
        }
        for ((index, frame) in candidate.frames.withIndex()) frame.inventory?.resources?.let { next ->
            val old = checkNotNull(record.frames[index].inventory).resources
            next.observedLoadGeneration = old.observedLoadGeneration; next.mustReconcileLoad = old.mustReconcileLoad
        }
        candidate.primary.resources?.observedLoadGeneration = record.primary.resources?.observedLoadGeneration
        candidate.reaction.handledDamage = record.reaction.handledDamage
        if (!policyOnly(request.change)) {
        val problem = PlantingAccounting.observe(candidate,npc) ?: FarmAccounting.observe(candidate, npc) ?: FoodAccounting.observe(candidate, npc) ?: TaskMining.observeInventory(candidate, npc) ?: TaskLumberjack.observeInventory(candidate, npc) ?: TaskTransport.observeInventory(candidate, npc) ?: InventoryTaskCapture.observe(candidate, npc)
        require(problem == null) { problem.orEmpty() }
        candidate.primary.lumberjack?.let {
            require(LumberjackWorkReconciliation.observePending(it, world) == null) { "pending old work changed before amendment" }
            if ((record.primary.definition as LumberjackTaskDefinition).version == 1) {
                val container = world.observeBlockContainer(it.job.chestPosition)
                require(container != null && TaskLumberjack.containerCounts(container) == it.containerCounts && container.containerSize == it.containerSize) { "published wood container checkpoint changed before amendment" }
            }
        }
        if (candidate.primary.resources != null) upgradeDelivery(checkNotNull(candidate.primary.resources), candidate.primary.definition as DeliveryTaskDefinition, npc, world)
        }
        candidate.amendments.pending = null
        val old = record.primary.definition
        val frame = candidate.primary
        val newObjective = (request.change as? TaskChange.Replace)?.objective == ObjectiveChangeMode.NEW_OBJECTIVE
        if (newObjective) {
            candidate.amendments.objectives.add(report(candidate))
            candidate.amendments.objectiveId = request.requestId
        }
        if (!policyOnly(request.change)) when (proposed) {
            is ExplorerTaskDefinition -> throw IllegalArgumentException("exploration contract cannot be replaced during captured work")
            is FishingTaskDefinition -> throw IllegalArgumentException("fishing pond/quota cannot be replaced during captured work")
            is PrepareFieldTaskDefinition -> throw IllegalArgumentException("field geometry cannot be replaced during captured work")
            is MachineTaskDefinition -> throw IllegalArgumentException("machine feed contract cannot be replaced during captured work")
            is PlantingTaskDefinition -> {
                require(proposed.contains(snapshot.position)) { "NPC is outside proposed planting travel boundary" }
                for(position in proposed.work.sources?.positions.orEmpty()) {
                    val container=world.observeBlockContainer(position)
                    require(container != null && !container.isTruncated && container.containerSize in 1..64) { "proposed sapling source is unavailable" }
                }
                if(newObjective) frame.planting=requireNotNull(TaskPlanting.capture(npc)) { "cannot capture new planting inventory" }
                val state=checkNotNull(frame.planting)
                require((state.checkpoints.keys+proposed.work.sources?.positions.orEmpty()).distinct().size <= 32) { "planting source history exceeds bounds" }
                if(state.phase == PlantingPhase.RETURN && state.stop == null && !state.goal(proposed) && state.cursor < proposed.work.sites.size) state.phase=PlantingPhase.SELECT
            }
            is FarmTaskDefinition -> {
                require(proposed.contains(snapshot.position)) { "NPC is outside proposed farm travel boundary" }
                for (position in proposed.destinations.positions+proposed.work.seedSources?.positions.orEmpty()) {
                    val container=world.observeBlockContainer(position)
                    require(container != null && !container.isTruncated && container.containerSize in 1..64) { "proposed farm seed source/recipient is unavailable" }
                }
                if (newObjective) frame.farming=requireNotNull(TaskFarm.capture(npc,proposed)) { "cannot capture new farm inventory" }
                val state=checkNotNull(frame.farming)
                require((state.checkpoints.keys+proposed.destinations.positions).distinct().size <= 32) { "farm recipient history exceeds bounds" }
                state.selectedContainer=null
                if (state.phase == FarmPhase.RETURN && state.stop == null && state.cycle < proposed.work.cycles && !state.goal(proposed)) {
                    state.exhausted=false; state.cursor=0; state.cycleDone.clear(); state.phase=FarmPhase.WAIT_GROWTH
                    state.growthRemaining=proposed.work.growthWaitTicks; state.nextGrowthCheck=proposed.work.growthCheckTicks
                }
            }
            is FoodTaskDefinition -> {
                require(proposed.contains(snapshot.position)) { "NPC is outside proposed food travel boundary" }
                for (position in proposed.destinations.positions+(proposed.work as? FoodWorkOrder.Stored)?.sources?.positions.orEmpty()) {
                    val container=world.observeBlockContainer(position)
                    require(container != null && !container.isTruncated && container.containerSize in 1..64) { "proposed food source/recipient is unavailable" }
                }
                if (newObjective) frame.food=requireNotNull(TaskFood.capture(npc,proposed)) { "cannot capture new food inventory" }
                val state=checkNotNull(frame.food)
                require((state.checkpoints.keys+proposed.destinations.positions).distinct().size <= 32) { "food recipient history exceeds bounds" }
                require((state.sourceCheckpoints.keys+(proposed.work as? FoodWorkOrder.Stored)?.sources?.positions.orEmpty()).distinct().size <= 32) { "food source history exceeds bounds" }
                state.selectedContainer=null; state.selectedSource=null
                if (state.phase == FoodPhase.RETURN && !state.goal(proposed)) {
                    if (proposed.work is FoodWorkOrder.Stored) { state.exhausted=false; state.stop=null }
                    if (!state.exhausted && state.stop == null) state.phase=FoodPhase.SELECT
                }
            }
            is MiningTaskDefinition -> {
                require(proposed.contains(snapshot.position)) { "NPC is outside proposed mining travel boundary" }
                for (position in proposed.destinations.positions) {
                    val container = world.observeBlockContainer(position)
                    require(container != null && !container.isTruncated && container.containerSize in 1..64) { "proposed mining recipient is unavailable" }
                }
                if (newObjective) frame.mining = requireNotNull(TaskMining.capture(npc)) { "cannot capture new mining inventory" }
                val state = checkNotNull(frame.mining)
                require((state.checkpoints.keys + proposed.destinations.positions).distinct().size <= 32) { "mining recipient history exceeds bounds" }
                state.selectedContainer = null
                if (state.phase == MiningPhase.RETURN && !state.exhausted && state.stop == null && !state.goal(proposed)) state.phase = MiningPhase.SELECT
            }
            is InventoryTaskDefinition -> require(proposed == old) { "inventory request cannot change during its captured work" }
            is TransportTaskDefinition, is DeliveryTaskDefinition -> {
                val route = CargoRoute.from(proposed)
                require(route.contains(snapshot.position)) { "travel bounds: NPC is outside the proposed fixed boundary" }
                for (position in route.destinations.positions + route.sources?.positions.orEmpty()) {
                    val container = world.observeBlockContainer(position)
                    require(container != null && !container.isTruncated && container.containerSize in 1..64 && world.observeBlock(position) != null) { "container: proposed endpoint $position is unavailable or unbounded" }
                }
                frame.transport = if (newObjective) checkNotNull(TaskTransport.capture(npc, proposed)) else {
                    frame.transport ?: upgradeDelivery(checkNotNull(frame.resources), old as DeliveryTaskDefinition, npc, world)
                }
                frame.resources = null
                val state = checkNotNull(frame.transport)
                require((state.checkpoints.keys + route.destinations.positions + route.sources?.positions.orEmpty()).distinct().size <= TransportLedger.MAX_ENDPOINTS) { "container history exceeds 32 endpoints" }
                state.selected = null; state.deferred.clear(); state.lastProblem = null
                state.phase = if (route.sources == null || state.ledger.deliverable(route.keepAtLeast) > 0) TransportPhase.DESTINATION else TransportPhase.SOURCE
                state.ledger.observedLoadGeneration = npc.inventoryLoadSnapshot()?.generation; state.ledger.mustReconcileLoad = false
            }
            is LumberjackTaskDefinition -> {
                val previous = checkNotNull(frame.lumberjack)
                val container = world.observeBlockContainer(proposed.destination)
                val counts = container?.let(TaskLumberjack::containerCounts)
                require(counts != null) { "recipient: proposed wood output is unavailable" }
                require(proposed.destination in previous.deliveries || previous.deliveries.size < 32) { "wood recipient history exceeds 32 endpoints" }
                for (position in proposed.supplySources?.positions.orEmpty()) require(world.observeBlockContainer(position)?.let(TaskLumberjack::containerCounts) != null) { "supplies: proposed source $position is unavailable" }
                val priorDefinition = old as LumberjackTaskDefinition
                if (newObjective) {
                    val next = TaskLumberjack.capture(npc, proposed) ?: throw IllegalArgumentException("cannot establish the new wood inventory checkpoint")
                    next.observedRemovedBlocks.addAll(previous.observedRemovedBlocks)
                    next.pastAreas.addAll(previous.pastAreas)
                    if (next.observedRemovedBlocks.any { !proposed.area.contains(it) }) next.pastAreas.add(priorDefinition.area)
                    require(next.pastAreas.size <= 8) { "work-area history exceeds eight regions" }
                    frame.lumberjack = next; frame.planting=null
                } else {
                    if (priorDefinition.area != proposed.area) {
                        require(previous.pastAreas.size < 8) { "work-area history exceeds eight regions" }
                        previous.pastAreas.add(priorDefinition.area)
                        previous.job.scanCursor = 0; previous.job.deferredWood.clear()
                        previous.job.targetPosition = null; previous.job.trunkBasePosition = null; previous.job.blockedLogPosition = null
                        previous.job.miningStance = null; previous.job.rejectedMiningStances.clear()
                    }
                    previous.containerSize = container.containerSize; previous.containerCounts = counts
                    if (priorDefinition.supplySources != proposed.supplySources || priorDefinition.version == 1) previous.supplies = LumberjackSupplyState()
                    previous.job.workSelection = LumberjackWorkSelection(proposed.area, proposed.wood)
                    if(previous.replantDefinition == null) { previous.job.finishAfterCurrentTree = false; previous.job.executionFinished = false }
                    LumberjackSupply.bind(proposed, previous)
                }
                checkNotNull(frame.lumberjack).resources.observedLoadGeneration = npc.inventoryLoadSnapshot()?.generation
                checkNotNull(frame.lumberjack).resources.mustReconcileLoad = false
            }
            is NavigateTaskDefinition -> require(TaskNavigator.distanceSquared(snapshot.position, proposed.destination) <= 64.0 * 64.0 && world.observeBlock(NpcBlockPosition(kotlin.math.floor(proposed.destination.x).toInt(), kotlin.math.floor(proposed.destination.y).toInt(), kotlin.math.floor(proposed.destination.z).toInt())) != null) { "destination: new navigation endpoint is unavailable or beyond 64 blocks" }
            is AttackTaskDefinition -> {
                require(io.samcnpc.behavior.combat.CombatTargetSelector.exact(snapshot, world, proposed.targetUuid,
                    io.samcnpc.behavior.combat.CombatTargetSelector.Area(proposed.anchor, proposed.leash), proposed.allowPlayers, requireVisible = false) != null) { "target: current enemy is unavailable or outside amended policy" }
            }
            is CombatMissionDefinition -> {
                require(proposed.area().contains(snapshot.position)) { "mission boundary no longer contains NPC" }
                require(CombatMissionTargets.subjectProblem(proposed, snapshot, world) == null) { "mission subject is unavailable" }
                if (proposed is AreaAttackTaskDefinition && proposed.quota > (old as AreaAttackTaskDefinition).quota) frame.combat?.returning = false
            }
        }
        if (request.change is TaskChange.Reaction) {
            val problem = TaskCombatReactions.revise(candidate, npc, world, request.change.policy)
            require(problem == null) { problem.orEmpty() }
        }
        if (request.change is TaskChange.Logistics) {
            val problem = TaskLogistics.revise(candidate, npc, request.change.policy)
            require(problem == null) { problem.orEmpty() }
        }
        frame.definition = proposed
        if (request.change is TaskChange.ExtendTime) frame.remainingTicks += request.change.ticks
        if (request.change is TaskChange.Tactics) {
            for (part in candidate.frames.drop(1)) part.definition = TaskChanges.proposed(part.definition, request.change)
            candidate.reaction.policy = candidate.reaction.policy.copy(tactics = request.change.tactics)
        }
        candidate.amendments.revision++
        candidate.detail = "amended revision=${candidate.amendments.revision}; objective=${candidate.amendments.objectiveId}; completed effects and remaining budget retained"
        candidate.amendments.receipts.add(TaskAmendmentReceipt(request, candidate.amendments.revision, TaskAmendmentOutcome.APPLIED, candidate.detail))
        // The durable candidate must be independently loadable before the live record is touched.
        TaskCodec.read(TaskCodec.write(candidate))
        return candidate
    }
    private fun upgradeDelivery(progress: ResourceProgress, old: DeliveryTaskDefinition, npc: NpcFacade, world: NpcWorldView): TransportTaskState {
        val container = world.observeBlockContainer(old.destination) ?: throw IllegalArgumentException("old recipient is unavailable for published checkpoint migration")
        val loaded = npc.inventoryLoadSnapshot()
        if (loaded != null) require(progress.reconcileLoadedInventory(loaded.generation, loaded.inventory.sumOf { if (it.itemId == old.itemId) it.count else 0 }) == null) { "loaded carried delivery checkpoint differs" }
        require(progress.reconcile(TaskDelivery.inventoryCount(npc, old.itemId), TaskDelivery.containerCount(container, old.itemId), container.containerSize) == null) { "old carried delivery checkpoint differs; no migration/replay" }
        val credit = if (progress.delivered > 0) LegacyDeliveryCredit(old.destination, progress.delivered, checkNotNull(progress.receipt)) else null
        val rows = if (credit == null) linkedMapOf() else linkedMapOf(old.destination to progress.delivered)
        val ledger = TransportLedger(old.itemId, progress.initial, retained = progress.retained, delivered = progress.delivered,
            deliveries = rows, initialCargo = progress.initial, legacyCredit = credit)
        require(ledger.valid()) { "published delivery credit cannot be migrated" }
        return TransportTaskState(ledger, TransportPhase.DESTINATION)
    }
    private fun report(record: TaskRecord): TaskObjectiveReport {
        val definition = record.primary.definition
        val cargo = record.primary.transport?.ledger
        val wood = record.primary.lumberjack
        val legacy = record.primary.resources
        val mining = record.primary.mining
        val food = record.primary.food
        val farming = record.primary.farming
        val planting=record.primary.planting
        val resources = when {
            cargo != null -> mapOf(cargo.itemId to HarvestResource(cargo.initial, cargo.incidentalGained, cargo.withdrawn, 0, cargo.delivered, cargo.incidentalLost + cargo.cargoLost, cargo.retained))
            planting != null && wood == null -> planting.resources.entries
            farming != null -> farming.resources.physical.entries
            food != null -> food.resources.physical.entries
            mining != null -> mining.resources.physical.entries
            wood != null -> wood.resources.entries
            legacy != null -> mapOf((definition as DeliveryTaskDefinition).itemId to HarvestResource(legacy.initial, delivered = legacy.delivered, retained = legacy.retained))
            else -> emptyMap()
        }
        val deliveries = when {
            cargo != null -> cargo.deliveries.mapValues { mapOf(cargo.itemId to it.value) }
            planting != null && wood == null -> planting.deliveries.toMap()
            farming != null -> farming.deliveries.toMap()
            food != null -> food.deliveries.toMap()
            mining != null -> mining.deliveries.toMap()
            wood != null -> wood.deliveries.toMap()
            legacy != null && legacy.delivered > 0 -> mapOf((definition as DeliveryTaskDefinition).destination to mapOf(definition.itemId to legacy.delivered))
            else -> emptyMap()
        }
        val confirmed = if (planting != null && definition is PlantingTaskDefinition) planting.completed(definition) else if (farming != null) farming.delivered(definition as FarmTaskDefinition) else if (food != null) food.delivered(definition as FoodTaskDefinition) else if (mining != null) mining.confirmed(definition as MiningTaskDefinition) else cargo?.delivered ?: wood?.resources?.delivered((definition as LumberjackTaskDefinition).wood) ?: legacy?.delivered ?: record.primary.combat?.defeatedTargets?.size ?: 0
        val result = TaskObjectiveReport(record.amendments.objectiveId, record.amendments.revision, definition, confirmed, resources, deliveries,
            cargo?.describe(CargoRoute.from(definition).quantity) ?: record.detail, mining?.let(MiningObjectiveEvidence::capture), food?.let(FoodObjectiveEvidence::capture), farming?.let(FarmObjectiveEvidence::capture), planting?.let { PlantingObjectiveEvidence.capture(it,if(definition is PlantingTaskDefinition) definition else checkNotNull(wood?.replantDefinition)) })
        TaskObjectiveCodec.read(TaskObjectiveCodec.write(result))
        return result
    }
}
