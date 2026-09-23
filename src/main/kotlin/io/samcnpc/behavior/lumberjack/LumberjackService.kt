package io.samcnpc.behavior.lumberjack

import com.mojang.logging.LogUtils
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarKernel
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarResultCode
import io.samcnpc.behavior.kernel.navigation.NeighborRepulsionKernel
import io.samcnpc.behavior.kernel.navigation.UpwardStareRecoveryKernel
import io.samcnpc.behavior.kernel.navigation.RouteProgressWatchdog
import io.samcnpc.behavior.kernel.navigation.isClearPlayerStandingCell
import io.samcnpc.behavior.kernel.navigation.isLeafOrSupportedSnowObstacle
import io.samcnpc.behavior.kernel.work.SpatialWorkClaimKernel
import io.samcnpc.behavior.kernel.work.TrackedBlockBreakKernel
import io.samcnpc.behavior.kernel.inventory.InventoryBaselineKernel
import io.samcnpc.behavior.kernel.inventory.InventoryBaselineKernel.destinationFor
import io.samcnpc.behavior.lumberjack.tree.highestRemainingLumberjackTrunkLog
import io.samcnpc.behavior.lumberjack.tree.initialLumberjackTrunkLog
import io.samcnpc.behavior.lumberjack.tree.isLowestLumberjackWoodLog
import io.samcnpc.behavior.lumberjack.tree.isLumberjackWorkBlock
import io.samcnpc.behavior.lumberjack.tree.isLumberjackWoodLog
import io.samcnpc.behavior.lumberjack.tree.lowestRemainingLumberjackStep
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.UnresolvedWorkStore
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionCompletedEvent
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockContainerSlot
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcControlInput
import io.samcnpc.core.api.NpcEntityQuery
import io.samcnpc.core.api.NpcEquipmentDestination
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcItemPickupCheckEvent
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcSnapshot
import io.samcnpc.core.api.NpcToolKind
import io.samcnpc.core.api.NpcWorldView
import net.minecraft.server.MinecraftServer
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import java.util.UUID
import kotlin.math.floor

/**
 * Deliberately small deployment-test policy. It is Behavior code, uses only bounded Core facts
 * and explicit Core actions, and is removed cleanly by dropping the Behavior JAR.
 */
internal object LumberjackService {
    const val PACK_ID: String = "samcnpc:demo_lumberjack"
    private val routeProgress = RouteProgressWatchdog<LumberjackDemoPhase>(SEMANTIC_PATH_PROGRESS_DISTANCE_SQR)
    private val upwardStareRecovery = UpwardStareRecoveryKernel<LumberjackDemoPhase>()
    private val trackedBlockBreaks = TrackedBlockBreakKernel()
    private val treeWorkClaims = io.samcnpc.behavior.kernel.work.HarvestWorkClaims.kernel
    internal fun incidentalDropFilter(npc: io.samcnpc.core.api.NpcSnapshot, taskId: UUID, radius: Double): (NpcPosition) -> Boolean =
        treeWorkClaims.incidentalCollectionFilter(npc.npcUuid, taskId, npc.dimensionId, npc.position, radius, npc.gameTime)

    private val neighborRepulsion = NeighborRepulsionKernel()

    @SubscribeEvent
    fun onActionCompleted(event: NpcActionCompletedEvent) {
        trackedBlockBreaks.onActionCompleted(event)
    }

    @SubscribeEvent
    fun releaseTransientKernels(event: ServerStoppingEvent) {
        treeWorkClaims.clear()
        neighborRepulsion.clear()
    }

    /** Drop transient continuation before cancellation publishes its old mechanical result. */
    fun suspendExecution(npcUuid: UUID) {
        routeProgress.clear(npcUuid)
        upwardStareRecovery.clear(npcUuid)
        trackedBlockBreaks.clear(npcUuid)
        treeWorkClaims.release(npcUuid)
        neighborRepulsion.clear(npcUuid)
        val server = BehaviorRuntimeService.serverOrNull() ?: return
        val job = LumberjackDemoStore.forServer(server).jobFor(npcUuid) ?: return
        job.climbForwardTicks = 0
        job.chestApproach = null
        job.pillarSession?.revalidateSupports = true
    }

    fun start(server: MinecraftServer, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val taskStore = io.samcnpc.behavior.task.TaskStore.forServer(server)
        val taskProblem = taskStore.problemFor(npc.npcUuid)
        if (taskProblem != null) return NpcActionResult.rejected(taskProblem, NpcActionCode.NOT_READY)
        val primaryTask = taskStore.get(npc.npcUuid)
        if (primaryTask != null && !primaryTask.status.terminal) {
            return NpcActionResult.rejected("this NPC already has an active or paused primary task", NpcActionCode.CONFLICT)
        }
        if (PACK_ID !in BehaviorRuntimeService.activePackIds()) {
            val reload = BehaviorRuntimeService.reload()
            if (!reload.accepted || PACK_ID !in BehaviorRuntimeService.activePackIds()) {
                return NpcActionResult.rejected(
                    "the built-in lumberjack demo pack is not active: ${reload.messages.joinToString(" | ")}",
                    NpcActionCode.NOT_READY,
                )
            }
        }
        val unresolved = UnresolvedWorkStore.forServer(server)
        val reportProblem = unresolved.problem
        if (reportProblem != null) return NpcActionResult.rejected(reportProblem, NpcActionCode.NOT_READY)
        unresolved.reconcileAbsent(npc.npcUuid, npc.snapshot().dimensionId, world)
        val store = LumberjackDemoStore.forServer(server)
        val savedProblem = store.problemFor(npc.npcUuid)
        if (savedProblem != null) return NpcActionResult.rejected(savedProblem, NpcActionCode.NOT_READY)
        if (store.jobFor(npc.npcUuid) != null) {
            return NpcActionResult.rejected("this NPC already has an active lumberjack demo job", NpcActionCode.CONFLICT)
        }
        routeProgress.clear(npc.npcUuid)
        upwardStareRecovery.clear(npc.npcUuid)
        trackedBlockBreaks.clear(npc.npcUuid)
        treeWorkClaims.release(npc.npcUuid)
        neighborRepulsion.clear(npc.npcUuid)
        val snapshot = npc.snapshot()
        val chest = findNearestChest(snapshot.position, world)
            ?: return NpcActionResult.rejected("no chest or trapped chest was found in the bounded 50x50 search area", NpcActionCode.NOT_FOUND)
        val previousPacks = BehaviorRuntimeService.assignedPacks(server, npc.npcUuid)
        val stored = store.put(createJob(npc, chest, previousPacks))
        if (stored.status != NpcActionStatus.SUCCEEDED) return stored
        val assignment = BehaviorRuntimeService.assignPacks(server, npc.npcUuid, listOf(PACK_ID))
        if (assignment.status != NpcActionStatus.SUCCEEDED) {
            store.remove(npc.npcUuid)
            return assignment
        }
        return NpcActionResult.succeeded("lumberjack demo started; chest at ${chest.x}, ${chest.y}, ${chest.z}")
    }

    internal fun createJob(
        npc: NpcFacade,
        chest: NpcBlockPosition,
        previousPacks: List<String>,
        center: NpcBlockPosition? = null,
    ): LumberjackDemoJob {
        val snapshot = npc.snapshot()
        return LumberjackDemoJob(
            npcUuid = npc.npcUuid,
            dimensionId = snapshot.dimensionId,
            chestPosition = chest,
            workCenter = center ?: NpcBlockPosition(floor(snapshot.position.x).toInt(), floor(snapshot.position.y).toInt(), floor(snapshot.position.z).toInt()),
            previousPackIds = previousPacks,
            phase = LumberjackDemoPhase.TRAVEL_TO_CHEST,
            scanCursor = 0,
            pickupTicks = 0,
            resumeWorkAfterDeposit = false,
            targetPosition = null,
            trunkBasePosition = null,
            blockedLogPosition = null,
            miningStance = null,
            rejectedMiningStances = mutableListOf(),
            initialTrunkTargetPending = false,
            climbJumpAttempts = 0,
            climbForwardTicks = 0,
            scaffoldMaterialRecovery = false,
            scaffoldMaterialRecoveryPending = false,
            failedWorkAttempts = 0,
            abandonTreeAfterPillarCleanup = false,
            initialWoodCounts = InventoryBaselineKernel.captureCounts(npc, ::isWoodItem),
            )
    }

    fun cancel(server: MinecraftServer, npc: NpcFacade): NpcActionResult {
        val store = LumberjackDemoStore.forServer(server)
        val job = store.jobFor(npc.npcUuid)
            ?: return NpcActionResult.rejected("this NPC has no active lumberjack demo job", NpcActionCode.NOT_READY)
        npc.stopControl()
        npc.abortBlockBreak()
        val preserved = LumberjackCleanupReport.preserve(server, job, npc.worldView(), "USER_CANCELLED")
        if (preserved.status != NpcActionStatus.SUCCEEDED) return preserved
        store.remove(npc.npcUuid)
        routeProgress.clear(npc.npcUuid)
        upwardStareRecovery.clear(npc.npcUuid)
        trackedBlockBreaks.clear(npc.npcUuid)
        treeWorkClaims.release(npc.npcUuid)
        neighborRepulsion.clear(npc.npcUuid)
        restorePreviousPacks(server, npc.npcUuid, job)
        return NpcActionResult.succeeded("lumberjack demo cancelled; prior behavior packs restored")
    }

    /** Compact, command-safe view of a bounded demo job; it contains no world mutation data. */
    fun status(server: MinecraftServer, npcUuid: java.util.UUID): String? {
        val store = LumberjackDemoStore.forServer(server)
        store.problemFor(npcUuid)?.let { return it }
        val residue = UnresolvedWorkStore.forServer(server).describe(npcUuid)
        val job = store.jobFor(npcUuid) ?: return residue
        val target = job.targetPosition?.let { "${it.x},${it.y},${it.z}" } ?: "-"
        val stance = job.miningStance?.let { "${it.x},${it.y},${it.z}" } ?: "-"
        val watchdog = routeProgress.status(npcUuid)
        val stalledTicks = watchdog?.noProgressTicks ?: 0
        val stallLimit = watchdog?.timeoutTicks ?: 0
        val upwardStare = upwardStareRecovery.status(npcUuid)
        val pillar = job.pillarSession?.let { session ->
            "task=${session.taskId}; state=${session.state}; target=${session.targetPosition.x},${session.targetPosition.y},${session.targetPosition.z}; " +
                "height=${session.placedPositions.size}/${session.estimatedLevels}; material=${session.materialItemId}; " +
                "activeSlot=${session.materialActiveSlot}; retries=${session.retries}; last=${session.lastResult}; " +
                "placed=${session.placedPositions.joinToString(prefix = "[", postfix = "]") { "${it.x},${it.y},${it.z}" }}"
        } ?: "-"
        return listOf(
            "phase=${job.phase}",
            "scanColumns=${job.scanCursor}/${WORK_OFFSETS.size}",
            "target=$target",
            "accessReturn=${job.accessReturnTarget}",
            "stance=$stance",
            "pickupTicks=${job.pickupTicks}",
            "jumpAttempts=${job.climbJumpAttempts}",
            "stepForwardTicks=${job.climbForwardTicks}",
            "recoveringWood=${job.scaffoldMaterialRecovery}",
            "recoveryPending=${job.scaffoldMaterialRecoveryPending}",
            "recoveryAttempts=${job.scaffoldRecoveryAttempts}/$MAX_SCAFFOLD_RECOVERY_ATTEMPTS",
            "chestAccess=${job.chestAccessTarget}; attempts=${job.chestAccessAttempts}/${LumberjackChestTravel.MAX_ACCESS_BLOCKS}; accessStage=${job.chestAccessStage}; accessTicks=${job.chestAccessTicks}",
            "abandonAfterCleanup=${job.abandonTreeAfterPillarCleanup}",
            "stalledTicks=$stalledTicks/$stallLimit",
            "upwardStare=$upwardStare",
            "rejectedStances=${job.rejectedMiningStances}",
            "pillar=$pillar",
            residue ?: "unresolvedSupports=0",
        ).joinToString(separator = "; ")
    }

    fun tick(server: MinecraftServer, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val store = LumberjackDemoStore.forServer(server)
        val job = store.jobFor(npc.npcUuid)
            ?: return NpcActionResult.rejected(store.problemFor(npc.npcUuid) ?: "no active lumberjack demo job", NpcActionCode.NOT_READY)
        val result = execute(server, npc, world, job) { store.jobFor(it) != null }
        store.markChanged()
        if (job.executionFinished) {
            store.remove(job.npcUuid)
            restorePreviousPacks(server, job.npcUuid, job)
        }
        return result
    }

    /** The caller owns persistence and final pack restoration; this step owns only supplied work. */
    internal fun execute(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
        isWorkingNpc: (UUID) -> Boolean,
    ): NpcActionResult {
        check(server.isSameThread) { "lumberjack execution requires the authoritative server thread" }
        if (job.npcUuid != npc.npcUuid || job.executionFinished) {
            return NpcActionResult.rejected("lumberjack work does not belong to this active body/job", NpcActionCode.NOT_READY)
        }
        val reportProblem = UnresolvedWorkStore.forServer(server).problem
        if (reportProblem != null) {
            npc.stopControl()
            npc.abortBlockBreak()
            return NpcActionResult.rejected(reportProblem, NpcActionCode.NOT_READY)
        }
        val snapshot = npc.snapshot()
        if (snapshot.dimensionId != job.dimensionId) {
            return finish(server, npc, job, NpcActionResult.failed("NPC changed dimension during lumberjack demo", NpcActionCode.WORLD_REJECTED))
        }
        val legacyTarget = job.targetPosition
        val legacyReturn = job.blockedLogPosition
        if (job.accessReturnTarget == null && legacyTarget != null && legacyReturn != null && world.isLeafOrSupportedSnowObstacle(legacyTarget)) {
            // Pre-v16 used one slot for two nested dependencies. Resolve its meaning with world
            // facts once available; preserve an outstanding stump/material prerequisite.
            val base = job.trunkBasePosition
            if (job.scaffoldMaterialRecovery && base != null && world.observeBlock(base)?.isLumberjackWoodLog() == true) {
                job.accessReturnTarget = base
            } else {
                job.accessReturnTarget = legacyReturn
                job.blockedLogPosition = null
            }

        }
        if (job.phase in TRUNK_WORK_PHASES && job.targetPosition in job.pillarSession?.placedPositions.orEmpty()) {
            // Also repair old saved plans that mistook recovered-wood supports for the trunk.
            return advanceTrunkPlan(world, job)
        }
        if (job.phase in CHEST_TRAVEL_PHASES && job.chestAccessTarget != null) {
            val access = LumberjackChestTravel.clearAccess(npc, world, job)
            routeProgress.clear(job.npcUuid)

            return finishIfMechanicalFailure(server, npc, job, access)
        }
        val onScaffold = job.pillarSession?.placedPositions?.isNotEmpty() == true
        val monitorUpwardStare = !onScaffold && job.phase in UPWARD_STARE_MONITORED_PHASES
        val resumeAfterDetour = if (job.phase == LumberjackDemoPhase.BREAK_LOG) LumberjackDemoPhase.TRAVEL_TO_LOG else job.phase
        when (val recovery = upwardStareRecovery.tick(npc, world, monitorUpwardStare, resumeAfterDetour)) {
            null -> Unit
            is UpwardStareRecoveryKernel.Step.Detouring -> {
                if (recovery.abortedBlockBreak) {
                    trackedBlockBreaks.clear(npc.npcUuid)
                }
                routeProgress.clear(npc.npcUuid)
                if (recovery.started) {
                    LOGGER.warn("Lumberjack wake detour started npc={} phase={} detail={}", npc.npcUuid, job.phase, recovery.result.detail)
                }
                return recovery.result
            }
            is UpwardStareRecoveryKernel.Step.Resume -> {
                job.phase = recovery.state
                routeProgress.clear(npc.npcUuid)

                return recovery.result
            }
            is UpwardStareRecoveryKernel.Step.Unavailable -> {
                if (recovery.abortedBlockBreak) {
                    trackedBlockBreaks.clear(npc.npcUuid)
                }
                LOGGER.warn("Lumberjack wake detour has no safe landing npc={} phase={}", npc.npcUuid, job.phase)
                return recovery.result
            }
            is UpwardStareRecoveryKernel.Step.Failed -> {
                return finish(server, npc, job, recovery.result)
            }
        }
        maintainTreeClaim(npc, job, snapshot)?.let { result -> return result }
        gentlySeparateNearbyLumberjacks(npc, world, job, snapshot, isWorkingNpc)?.let { result -> return result }
        if (hasStalledMovement(job, snapshot)) {
            return recoverFromStalledMovement(server, npc, world, job)
        }
        clearFoliageForSlowRoute(npc, world, job)?.let { result -> return finishIfMechanicalFailure(server, npc, job, result) }
        if (shouldReturnWoodForCapacity(npc, job)) {
            return beginCapacityWoodReturn(job)
        }
        return when (job.phase) {
            LumberjackDemoPhase.TRAVEL_TO_CHEST -> travelToChest(server, npc, world, job)
            LumberjackDemoPhase.PREPARE_EQUIPMENT -> prepareEquipment(server, npc, world, job)
            LumberjackDemoPhase.SEARCH_WOOD -> findNextLog(npc, world, job)
            LumberjackDemoPhase.TRAVEL_TO_LOG -> travelToLog(server, npc, world, job)
            LumberjackDemoPhase.BREAK_LOG -> breakLog(server, npc, world, job)
            LumberjackDemoPhase.CLIMB_TRUNK -> climbTrunk(server, npc, world, job)
            LumberjackDemoPhase.COLLECT_LOG_DROP -> collectLogDrop(server, npc, world, job)
            LumberjackDemoPhase.COLLECT_TREE_DROPS -> collectTreeDrops(npc, world, job)
            LumberjackDemoPhase.RETURN_TO_CHEST -> returnToChest(server, npc, world, job)
            LumberjackDemoPhase.DEPOSIT_WOOD -> depositWood(server, npc, world, job)
            LumberjackDemoPhase.PILLAR_UP -> advancePillar(server, npc, world, job)
            LumberjackDemoPhase.PILLAR_CLEANUP -> cleanPillar(server, npc, world, job)
        }
    }

    fun findNearestChest(origin: NpcPosition, world: NpcWorldView): NpcBlockPosition? {
        val centerX = floor(origin.x).toInt()
        val centerY = floor(origin.y).toInt()
        val centerZ = floor(origin.z).toInt()
        for ((offsetX, offsetZ) in CHEST_OFFSETS) {
            for (offsetY in CHEST_HEIGHT_OFFSETS) {
                val position = NpcBlockPosition(centerX + offsetX, centerY + offsetY, centerZ + offsetZ)
                val observation = world.observeBlock(position) ?: continue
                if (observation.hasContainer && observation.blockId in CHEST_BLOCK_IDS) {
                    return position
                }
            }
        }
        return null
    }

    private fun travelToChest(server: MinecraftServer, npc: NpcFacade, world: NpcWorldView, job: LumberjackDemoJob): NpcActionResult {
        if (!job.externalSuppliesAllowed) {
            job.phase = LumberjackDemoPhase.PREPARE_EQUIPMENT
            return NpcActionResult.running("preparing carried equipment; no external supply source authorized")
        }
        when (val progress = LumberjackChestTravel.move(npc, world, job)) {
            MoveTowardProgress.ARRIVED -> Unit
            MoveTowardProgress.MOVING -> return NpcActionResult.running("walking to the selected chest")
            is MoveTowardProgress.FAILED -> return finish(server, npc, job, progress.result)
        }
        npc.stopControl()
        job.phase = LumberjackDemoPhase.PREPARE_EQUIPMENT

        return NpcActionResult.running("arrived at the selected chest")
    }

    private fun prepareEquipment(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val chest = if (job.externalSuppliesAllowed) world.observeBlockContainer(job.chestPosition)
            ?: return finish(server, npc, job, NpcActionResult.failed("authorized supply container is no longer readable", NpcActionCode.NOT_FOUND)) else null
        for (destination in ARMOR_DESTINATIONS) {
            if (!npc.equipmentKnowledge().itemAt(destination).isEmpty) {
                continue
            }
            val inventoryArmor = npc.inventoryContents().firstOrNull { it.knowledge.armorDestination == destination }
            if (inventoryArmor != null) {
                return finishIfMechanicalFailure(server, npc, job, npc.equipFromInventory(inventoryArmor.slot, destination))
            }
            val chestArmor = chest?.slots?.firstOrNull { it.knowledge.armorDestination == destination && !it.stack.isEmpty }
            if (chestArmor != null) {
                return finishIfMechanicalFailure(
                    server,
                    npc,
                    job,
                    npc.moveBlockContainerToInventory(NpcBlockContainerSlot(job.chestPosition, chestArmor.slot), 1),
                )
            }
        }
        val carriedAxe = npc.inventoryContents().firstOrNull { it.knowledge.toolKind == NpcToolKind.AXE && !it.stack.isEmpty }
        val workSettings = npc.snapshot()
        if (carriedAxe == null && !workSettings.bareHandsMiningOnly) {
            val chestAxe = chest?.slots?.firstOrNull { it.knowledge.toolKind == NpcToolKind.AXE && !it.stack.isEmpty }
            if (chestAxe == null && !workSettings.ignoreMissingMiningTool) {
                return finish(server, npc, job, NpcActionResult.failed("no axe in carried inventory or authorized supply is available", NpcActionCode.MISSING_RESOURCE))
            }
            if (chestAxe != null) return finishIfMechanicalFailure(
                server,
                npc,
                job,
                npc.moveBlockContainerToInventory(NpcBlockContainerSlot(job.chestPosition, chestAxe.slot), 1),
            )
        }
        if (npc.inventoryContents().none { !it.stack.isEmpty && TemporaryPillarKernel.isPrimaryMaterial(it.knowledge) }) {
            // The supplied cheap blocks (dirt/cobblestone/netherrack and data-tagged peers) are
            // the normal scaffold source. Logs stay in the `AVOID` class until this NPC actually
            // fells and collects them as its bounded last-resort recovery material.
            val chestMaterial = chest?.slots?.firstOrNull { !it.stack.isEmpty && TemporaryPillarKernel.isPrimaryMaterial(it.knowledge) }
            if (chestMaterial != null) {
                return finishIfMechanicalFailure(
                    server,
                    npc,
                    job,
                    npc.moveBlockContainerToInventory(NpcBlockContainerSlot(job.chestPosition, chestMaterial.slot), chestMaterial.stack.count),
                )
            }
        }
        // Shared supplies must not lose an unrelated tool merely because a scaffold might need
        // one. Only loaded tool facts for carried primary material authorize this preparation.
        val cleanupTools = linkedSetOf<NpcToolKind>()
        for (entry in npc.inventoryContents()) {
            if (!entry.stack.isEmpty && TemporaryPillarKernel.isPrimaryMaterial(entry.knowledge)) {
                cleanupTools.addAll(entry.knowledge.placeableBlock?.effectiveToolKinds.orEmpty())
            }
        }
        for (kind in NpcToolKind.entries) {
            if (kind !in cleanupTools) continue
            if (workSettings.bareHandsMiningOnly) break
            val carried = npc.inventoryContents().any { it.knowledge.toolKind == kind && !it.stack.isEmpty }
            if (carried) continue
            val supplied = chest?.slots?.firstOrNull { it.knowledge.toolKind == kind && !it.stack.isEmpty } ?: continue
            return finishIfMechanicalFailure(server, npc, job,
                npc.moveBlockContainerToInventory(NpcBlockContainerSlot(job.chestPosition, supplied.slot), 1))
        }
        job.phase = LumberjackDemoPhase.SEARCH_WOOD

        return NpcActionResult.running("available equipment prepared; block work follows the server's tool settings")
    }

    private fun findNextLog(npc: NpcFacade, world: NpcWorldView, job: LumberjackDemoJob): NpcActionResult {
        // The old cursor represented one block in a 50x50x27 volume. At 96 blocks per tick it
        // could make a healthy NPC appear idle for half a minute before it reached a distant
        // column. A cursor now represents one vertical column: the same bounded observation
        // work completes in at most 20 admitted slices. The aggregate budget may defer a
        // slice; Core never selects a tree.
        val selection = job.workSelection
        job.scanUnavailable = false
        val columns = selection?.area?.columns ?: WORK_OFFSETS.size
        val height = selection?.area?.bounds?.height ?: LOG_HEIGHT_OFFSETS.size
        val perTick = minOf(LOG_SCAN_COLUMNS_PER_TICK, (3456 / height).coerceAtLeast(1))
        // A column may inspect each selected block and its support; reserve the complete slice.
        val planningCost = if (job.finishAfterCurrentTree) 0 else 2 * height * minOf(perTick, (columns - job.scanCursor).coerceAtLeast(0)) + 128
        if (planningCost > 0 && !io.samcnpc.behavior.runtime.BehaviorPlanning.admit(world, planningCost, io.samcnpc.behavior.kernel.work.PlanningKind.FOREST)) {
            npc.stopControl()
            return NpcActionResult.running("wood scan deferred by the shared planning budget; cursor and deadline retained")
        }
        repeat(perTick) {
            if (job.finishAfterCurrentTree || job.scanCursor >= columns) {
                if (!job.finishAfterCurrentTree) {
                    val deferred = LumberjackDeferredWork.candidate(job, world)
                    if (deferred != null) {
                        val claim = treeWorkClaims.renewOrClaim(npc.npcUuid, job.dimensionId, deferred.target, npc.snapshot().gameTime, job.workTaskId)
                        if (claim != SpatialWorkClaimKernel.Result.Acquired && claim != SpatialWorkClaimKernel.Result.Held) {
                            npc.stopControl()
                            return NpcActionResult.running("waiting within the original deadline for the now-unobstructed branch claim")
                        }
                        deferred.revisited = true
                        planTrunk(job, deferred.target, deferred.failedAttempts)
                        LOGGER.info("Lumberjack reconsiders unobstructed branch npc={} target={} clearedObstruction={}", job.npcUuid, deferred.target, deferred.obstruction)
                        return NpcActionResult.running("observed obstruction disappeared; revisiting the same permitted branch once without renewing task time")
                    }
                }
                val scaffold = job.pillarSession
                if (scaffold != null) {
                    TemporaryPillarKernel.beginCleanup(scaffold)
                    job.phase = LumberjackDemoPhase.PILLAR_CLEANUP
                } else {
                    clearTreePlan(job)
                    job.phase = LumberjackDemoPhase.RETURN_TO_CHEST
                }

                return NpcActionResult.running("bounded wood scan is finished; resolving any temporary scaffold before returning to the chest")
            }
            val column = if (selection == null) {
                val (offsetX, offsetZ) = WORK_OFFSETS[job.scanCursor]
                NpcBlockPosition(job.workCenter.x + offsetX, job.workCenter.y - 2, job.workCenter.z + offsetZ)
            } else selection.area.column(job.scanCursor)
            job.scanCursor += 1
            for (offsetY in 0 until height) {
                val position = NpcBlockPosition(column.x, column.y + offsetY, column.z)
                if (selection != null && !selection.area.contains(position)) continue
                val observation = world.observeBlock(position)
                if (selection != null && observation == null) {
                    job.scanCursor--
                    job.scanUnavailable = true
                    return NpcActionResult.running("selected work column is unavailable; scan remains pending")
                }
                if (observation != null && observation.isLumberjackWoodLog() &&
                    (selection == null || selection.wood.matches(observation.blockId)) && world.isLowestLumberjackWoodLog(position)) {
                    when (val claim = treeWorkClaims.renewOrClaim(npc.npcUuid, job.dimensionId, position, npc.snapshot().gameTime, job.workTaskId)) {
                        SpatialWorkClaimKernel.Result.Acquired, SpatialWorkClaimKernel.Result.Held -> {
                            planTrunk(job, position)

                            return NpcActionResult.running(
                                "planned trunk at ${position.x}, ${position.y}, ${position.z}; approaching it before selecting the eye-height log",
                            )
                        }
                        SpatialWorkClaimKernel.Result.Pending, SpatialWorkClaimKernel.Result.Limited -> {
                            job.scanCursor--
                            npc.stopControl()
                            return NpcActionResult.running("collecting fair work claims before choosing this trunk")
                        }
                        is SpatialWorkClaimKernel.Result.Contested -> {
                            if (selection != null && claim.holderAnchor != position) {
                                // A neighboring reservation does not mean this trunk is being
                                // harvested. Keep the same column and fair request until that
                                // temporary exclusion clears, within the original finite task.
                                job.scanCursor--
                                npc.stopControl()
                                return NpcActionResult.running("neighboring work temporarily blocks this unstarted trunk; cursor and original deadline retained")
                            }
                            treeWorkClaims.release(npc.npcUuid, job.workTaskId)
                            // Another worker is using this trunk or its immediate scaffold zone. Keep
                            // scanning in the same bounded tick; no second NPC may turn it into a
                            // competing pillar job.
                            LOGGER.debug("Lumberjack skipped claimed tree npc={} tree={} holder={} holderTree={}", npc.npcUuid, position, claim.holderNpcUuid, claim.holderAnchor)
                        }
                    }
                }
            }
        }

        return NpcActionResult.running("scanning the bounded work area for wood (${job.scanCursor}/$columns columns)")
    }

    private fun planTrunk(job: LumberjackDemoJob, position: NpcBlockPosition, failedAttempts: Int = 0) {
        job.trunkBasePosition = position; job.targetPosition = position; job.blockedLogPosition = null
        job.miningStance = null; job.rejectedMiningStances.clear(); job.initialTrunkTargetPending = true
        job.climbJumpAttempts = 0; job.climbForwardTicks = 0; job.scaffoldMaterialRecovery = false
        job.scaffoldMaterialRecoveryPending = false; job.failedWorkAttempts = failedAttempts
        job.abandonTreeAfterPillarCleanup = false; job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
    }

    /** Renews a transient lease for a durable tree plan, yielding safely if another NPC won it. */
    private fun maintainTreeClaim(
        npc: NpcFacade,
        job: LumberjackDemoJob,
        snapshot: io.samcnpc.core.api.NpcSnapshot,
    ): NpcActionResult? {
        if (job.abandonTreeAfterPillarCleanup) {
            return null
        }
        val trunkBase = job.trunkBasePosition ?: return null
        return when (val claim = treeWorkClaims.renewOrClaim(npc.npcUuid, job.dimensionId, trunkBase, snapshot.gameTime, job.workTaskId)) {
            SpatialWorkClaimKernel.Result.Acquired, SpatialWorkClaimKernel.Result.Held -> null
            SpatialWorkClaimKernel.Result.Pending, SpatialWorkClaimKernel.Result.Limited -> {
                npc.stopControl()
                NpcActionResult.running("waiting for fair work-claim reconstruction before continuing the existing trunk")
            }
            is SpatialWorkClaimKernel.Result.Contested -> yieldContestedTree(npc, job, claim)
        }
    }

    /**
     * A contested tree is never fought over. If this NPC has placed temporary blocks, it first
     * dismantles only its recorded scaffold and then resumes the wider scan; otherwise it yields
     * immediately. This makes claim loss safe across server reloads as well as live encounters.
     */
    private fun yieldContestedTree(
        npc: NpcFacade,
        job: LumberjackDemoJob,
        claim: SpatialWorkClaimKernel.Result.Contested,
    ): NpcActionResult {
        npc.stopControl()
        routeProgress.clear(job.npcUuid)
        neighborRepulsion.clear(job.npcUuid)
        treeWorkClaims.release(job.npcUuid)
        val session = job.pillarSession
        if (session == null) {
            LOGGER.info("Lumberjack yields contested tree npc={} tree={} holder={} holderTree={}", job.npcUuid, job.trunkBasePosition, claim.holderNpcUuid, claim.holderAnchor)
            return resumeLogScan(job)
        }
        job.abandonTreeAfterPillarCleanup = true
        TemporaryPillarKernel.beginCleanup(session)
        job.phase = LumberjackDemoPhase.PILLAR_CLEANUP

        LOGGER.info("Lumberjack yields contested tree after scaffold cleanup npc={} tree={} holder={} holderTree={}", job.npcUuid, job.trunkBasePosition, claim.holderNpcUuid, claim.holderAnchor)
        return NpcActionResult.running("another lumberjack already owns this tree; removing this NPC's recorded temporary scaffold before yielding")
    }

    /**
     * Claims prevent shared trees; this tiny detour prevents two independent workers from
     * body-blocking each other on adjacent ones. It is intentionally not collision force: the
     * NPC walks three blocks away from the closest worker, rotating through safe cardinal cells.
     */
    private fun gentlySeparateNearbyLumberjacks(
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
        snapshot: io.samcnpc.core.api.NpcSnapshot,
        isWorkingNpc: (UUID) -> Boolean,
    ): NpcActionResult? {
        if (job.phase !in PERSONAL_SPACE_PHASES || job.pillarSession?.placedPositions?.isNotEmpty() == true) {
            neighborRepulsion.clear(job.npcUuid)
            return null
        }
        val neighbors = world.queryEntities(
            NpcEntityQuery(
                center = snapshot.position,
                radius = PERSONAL_SPACE_QUERY_RADIUS,
                limit = MAX_PERSONAL_SPACE_OBSERVATIONS,
            ),
        ).asSequence()
            .filter { observation -> observation.alive && isWorkingNpc(observation.uuid) }
            .map { observation -> observation.position }
            .toList()
        val detour = neighborRepulsion.nextTarget(
            npc.npcUuid,
            snapshot.gameTime,
            snapshot.position,
            neighbors,
            world::isClearPlayerStandingCell,
        ) ?: return null
        val navigation = npc.navigateTo(blockNavigationPosition(detour.target), PERSONAL_SPACE_NAVIGATION_SPEED)
        if (navigation.isFailure()) {
            neighborRepulsion.clear(npc.npcUuid)
            return null
        }
        routeProgress.clear(npc.npcUuid)
        if (detour.started) {
            LOGGER.info("Lumberjack makes personal-space detour npc={} direction={} target={}", npc.npcUuid, detour.direction.label, detour.target)
        }
        return NpcActionResult.running("giving a nearby lumberjack space with a short ${detour.direction.label} detour")
    }

    private fun travelToLog(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val target = job.targetPosition ?: return resumeLogScan(job)
        val observation = world.observeBlock(target)
        if (observation == null || !observation.isLumberjackWorkBlock()) {
            return if (job.accessReturnTarget != null) {
                resumeAccessWork(npc, job)
            } else if (job.blockedLogPosition != null) {
                resumeBlockedLog(npc, job)
            } else {
                advanceTrunkPlan(world, job)
            }
        }
        val trunkBase = job.trunkBasePosition ?: return resumeLogScan(job)
        val clearingFoliage = job.accessReturnTarget != null && world.isLeafOrSupportedSnowObstacle(target)
        val scaffoldStance = job.recordedScaffoldStance(npc.snapshot())
        if (scaffoldStance != null) {
            npc.stopControl()
            job.miningStance = scaffoldStance
            job.phase = LumberjackDemoPhase.BREAK_LOG

            return NpcActionResult.running("retaining the supported work stance; Core checks reach before further elevation or descent")
        }
        val stance = job.miningStance ?: if (clearingFoliage) {
            selectFoliageClearingStance(npc.snapshot().position, target, world)
        } else {
            selectMiningStance(npc.snapshot().position, trunkBase, world, rejectedStances(job))
        }
        if (stance == null) {
            if (clearingFoliage && isWithinDirectFoliageBreakRange(npc.snapshot().position, target)) {
                npc.stopControl()
                job.phase = LumberjackDemoPhase.BREAK_LOG

                return NpcActionResult.running("a leaf is inside the NPC collision corridor; clearing it from the current legal reach")
            }
            return if (clearingFoliage) {
                continueAfterUnreachableWorkBlock(world, job)
            } else {
                resumeLogScan(job)
            }
        }
        if (job.miningStance == null) {
            job.miningStance = stance

        }
        when (val progress = moveToMiningStance(npc, target, stance)) {
            MoveTowardProgress.ARRIVED -> Unit
            MoveTowardProgress.MOVING -> return NpcActionResult.running("walking to a clear mining stance beside supplied wood")
            is MoveTowardProgress.FAILED -> return finish(server, npc, job, progress.result)
        }
        npc.stopControl()
        if (job.initialTrunkTargetPending) {
            val firstLog = world.initialLumberjackTrunkLog(trunkBase, floor(npc.snapshot().eyePosition.y).toInt())
                ?: return resumeLogScan(job)
            job.targetPosition = firstLog
            job.initialTrunkTargetPending = false

            return NpcActionResult.running("arrived beside the trunk; selected its first eye-height log")
        }
        job.phase = LumberjackDemoPhase.BREAK_LOG

        return NpcActionResult.running("arrived at a clear mining stance beside supplied wood")
    }

    private fun breakLog(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val target = job.targetPosition ?: return resumeLogScan(job)
        val completion = trackedBlockBreaks.takeCompleted(job.npcUuid)
        if (completion != null) {
            if (completion.status != NpcActionStatus.SUCCEEDED) {
                // Leaves can decay between Core accepting an explicit break and its next
                // validation tick. The terminal action is then mechanically FAILED even though
                // the obstruction is already gone. Treat the observed air cell as resolved and
                // resume the exact deferred log instead of abandoning this whole tree.
                if (world.observeBlock(target)?.isLumberjackWorkBlock() != true) {
                    return continueAfterCompletedWorkBlock(npc, job)
                }
                return continueAfterUnreachableWorkBlock(world, job)
            }
        }
        val observation = world.observeBlock(target)
        if (observation == null || !observation.isLumberjackWorkBlock()) {
            return continueAfterCompletedWorkBlock(npc, job)
        }
        // Core owns the validity of an active player-like break. Behavior keeps the head pointed
        // at the caller-selected block so the visible pose and the actual work target agree.
        val activeBreak = npc.snapshot().blockBreak
        if (activeBreak != null) {
            return if (activeBreak.position == target) {
                val look = lookTowards(npc, blockCenter(target))
                if (look.isFailure()) {
                    return finish(server, npc, job, look)
                }
                npc.continueBlockBreak()
            } else {
                NpcActionResult.running("waiting for another explicit Core block-break action to finish")
            }
        }
        val stance = job.miningStance
        val snapshot = npc.snapshot()
        val clearingFoliage = job.accessReturnTarget != null && world.isLeafOrSupportedSnowObstacle(target)
        val atStance = stance != null && (
            isWithinPosition(snapshot.position, stance, MINING_STANCE_ARRIVAL_DISTANCE) ||
                job.isStandingOnPreservedStump(snapshot)
            ) || (clearingFoliage && isWithinDirectFoliageBreakRange(snapshot.position, target))
        if (!atStance) {
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

            return NpcActionResult.running("NPC left its clear mining stance; navigating back before mining")
        }
        val look = lookTowards(npc, blockCenter(target))
        if (look.isFailure()) {
            return finish(server, npc, job, look)
        }
        val started = npc.startBlockBreak(target)
        trackedBlockBreaks.trackStart(job.npcUuid, started)
        if (started.status == NpcActionStatus.REJECTED && started.code == NpcActionCode.OUT_OF_RANGE) {
            val scaffold = job.pillarSession
            if (scaffold != null && scaffold.placedPositions.isNotEmpty()) {
                if (target.y >= floor(npc.snapshot().position.y).toInt()) {
                    TemporaryPillarKernel.continueToTarget(scaffold, target)
                    job.phase = LumberjackDemoPhase.PILLAR_UP

                    return NpcActionResult.running("the next trunk log is still high; reusing the existing temporary work scaffold")
                }
                TemporaryPillarKernel.beginCleanup(scaffold)
                job.phase = LumberjackDemoPhase.PILLAR_CLEANUP

                LOGGER.info("Lumberjack begins scaffold descent npc={} from={} placed={}", job.npcUuid, npc.snapshot().position, scaffold.placedPositions)
                return NpcActionResult.running("the remaining supplied log is below the scaffold; descending one recorded temporary block at a time")
            }
            if (job.canClimbOntoPreservedStump(world) && !isStandingOnPreservedStump(snapshot.position, snapshot.onGround, job.trunkBasePosition ?: target)) {
                job.phase = LumberjackDemoPhase.CLIMB_TRUNK
                job.climbJumpAttempts = 0
                job.climbForwardTicks = 0
                job.scaffoldMaterialRecovery = false

                return NpcActionResult.running("the next supplied log is above ground reach; stepping onto the preserved stump")
            }
            if (job.isStandingOnPreservedStump(snapshot)) {
                // A lower side cannot improve vertical reach after a real stump landing.
                // Retain this useful footing instead of walking off and potentially losing
                // the only reachable route back on uneven terrain.
                return beginPillarOrContinue(npc, world, job)
            }
            val lowerStep = world.lowestRemainingLumberjackStep(job.trunkBasePosition, target)
            if (lowerStep != null) {
                job.blockedLogPosition = target
                job.targetPosition = lowerStep
                job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

                return NpcActionResult.running("freeing the lowest remaining trunk block before stepping onto the supplied upper log")
            }
            // A synchronous Core rejection is a real failed stance too. Remember it before
            // changing sides, or travel/break alternation keeps resetting the route watchdog.
            if (stance != null) rememberRejectedStance(job, stance)
            val trunkBase = job.trunkBasePosition ?: target
            val alternate = selectMiningStance(npc.snapshot().position, trunkBase, world, rejectedStances(job))
            if (alternate != null && job.failedWorkAttempts == 0) {
                job.miningStance = alternate
                job.failedWorkAttempts = 1
                job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

                return NpcActionResult.running("Core reports the supplied wood outside reach; trying another clear side")
            }
            return beginPillarOrContinue(npc, world, job)
        }
        if (started.status == NpcActionStatus.REJECTED && started.code == NpcActionCode.WORLD_REJECTED) {
            // Clear only the first exact ray obstruction: a leaf, or a snow layer sitting on a
            // leaf. A snowy canopy therefore resolves naturally as snow -> leaf -> log, never
            // as an arbitrary canopy clear.
            val obstruction = accessObstacleBlockingViewOf(npc, target, world)
            if (obstruction != null && deferAccessObstacle(job, obstruction)) {
                return NpcActionResult.running("a leaf or its snow layer blocks the supplied log; clearing that exact obstruction")
            }
            // A synchronous Core rejection is a real failed stance too. Remember it before
            // changing sides, or travel/break alternation keeps resetting the route watchdog.
            if (stance != null) rememberRejectedStance(job, stance)
            val trunkBase = job.trunkBasePosition ?: target
            val alternate = selectMiningStance(npc.snapshot().position, trunkBase, world, rejectedStances(job))
            if (alternate != null) {
                job.miningStance = alternate
                job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

                return NpcActionResult.running("Core reports the log is not visible; trying another clear mining stance")
            }
            return continueAfterUnreachableWorkBlock(world, job)
        }
        return finishIfMechanicalFailure(server, npc, job, started)
    }

    private fun climbTrunk(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val trunkBase = job.trunkBasePosition ?: return resumeLogScan(job)
        if (!job.canClimbOntoPreservedStump(world)) {
            return beginPillarOrContinue(npc, world, job)
        }
        val stumpTop = NpcBlockPosition(trunkBase.x, trunkBase.y + 1, trunkBase.z)
        val snapshot = npc.snapshot()
        if (isStandingOnPreservedStump(snapshot.position, snapshot.onGround, trunkBase)) {
            npc.stopControl()
            job.miningStance = stumpTop
            job.climbJumpAttempts = 0
            job.climbForwardTicks = 0
            job.scaffoldMaterialRecovery = false
            job.phase = LumberjackDemoPhase.BREAK_LOG

            return NpcActionResult.running("standing on the preserved stump; retrying the supplied upper log")
        }
        if (job.climbJumpAttempts > 0) {
            // Once launched, the destination is the stump, never the launch cell behind us.
            // The old 3D launch-distance check steered a rising body backwards, then mistook
            // reaching stump height for a landing and handed an airborne NPC to BREAK_LOG.
            if (snapshot.inWater || snapshot.inLava || snapshot.climbing || snapshot.riding ||
                (snapshot.onGround && job.climbForwardTicks == 0)
            ) {
                npc.stopControl()
                return beginPillarOrContinue(npc, world, job)
            }
            val turn = lookTowards(npc, blockCenter(stumpTop))
            if (turn.isFailure()) return finish(server, npc, job, turn)
            val centeredAboveStump = horizontalDistance(snapshot.position, trunkBase) <= STUMP_JUMP_CENTER_TOLERANCE &&
                snapshot.position.y >= stumpTop.y
            val motion = if (centeredAboveStump) npc.stopControl() else npc.applyControl(NpcControlInput(forward = 1.0F, strafe = 0.0F))
            if (motion.isFailure()) return finish(server, npc, job, motion)
            if (job.climbForwardTicks > 0) {
                job.climbForwardTicks -= 1

            }
            return NpcActionResult.running("completing the committed stump jump; waiting for an actual supported landing")
        }
        val launchCell = trunkLaunchCell(trunkBase, job.miningStance)
        if (!world.isClearPlayerStandingCell(launchCell)) {
            return continueAfterUnreachableWorkBlock(world, job)
        }
        if (!isWithinPosition(snapshot.position, launchCell, CLIMB_LAUNCH_ARRIVAL_DISTANCE)) {
            job.climbForwardTicks = 0
            // This is the one intentionally direct movement step in the policy. Ground
            // navigation regards the trunk-adjacent launch cell as obstructed and can circle it;
            // a player instead walks straight to the clear edge before jumping onto the log.
            val turn = lookTowards(npc, blockCenter(launchCell))
            if (turn.isFailure()) {
                return finish(server, npc, job, turn)
            }
            val forward = npc.applyControl(NpcControlInput(forward = 1.0F, strafe = 0.0F))
            if (forward.isFailure()) {
                return finish(server, npc, job, forward)
            }
            return NpcActionResult.running("walking directly to the clear side of the preserved stump")
        }
        val turn = lookTowards(npc, blockCenter(stumpTop))
        if (turn.isFailure()) {
            return finish(server, npc, job, turn)
        }
        val forward = npc.applyControl(NpcControlInput(forward = 1.0F, strafe = 0.0F))
        if (forward.isFailure()) {
            return finish(server, npc, job, forward)
        }
        if (!snapshot.onGround) {
            return NpcActionResult.running("waiting for real ground before starting the one permitted stump jump")
        }
        val jump = npc.jump()
        if (jump.isFailure()) {
            return finish(server, npc, job, jump)
        }
        job.climbJumpAttempts += 1
        job.climbForwardTicks = CLIMB_POST_JUMP_FORWARD_TICKS

        return NpcActionResult.running("moving forward and making one committed jump onto the preserved stump")
    }

    /**
     * A navigation request may be physically impossible even though its air-cell observation is
     * valid (for example, leaves, a snow ledge, or a trunk corner block the Mob hull). A vertical
     * hop is not route progress. This Behavior-only watchdog measures only horizontal reduction
     * to its supplied navigation goal and asks the policy to choose another side after a bounded,
     * distance-derived period without meaningful progress.
     */
    private fun hasStalledMovement(job: LumberjackDemoJob, snapshot: NpcSnapshot): Boolean {
        // A completed natural stump climb deliberately ends in the CLIMB_TRUNK phase for one
        // tick so that the executor can hand control back to BREAK_LOG. Treating that stable
        // work position as a navigation stall prevented the handoff and made high trunks loop.
        if (job.phase == LumberjackDemoPhase.CLIMB_TRUNK && job.isStandingOnPreservedStump(snapshot)) {
            routeProgress.clear(job.npcUuid)
            return false
        }
        if (job.phase !in MOVEMENT_PHASES) {
            routeProgress.clear(job.npcUuid)
            return false
        }
        val goal = movementGoal(job)
        if (goal == null) {
            routeProgress.clear(job.npcUuid)
            return false
        }
        val position = snapshot.position
        val distanceSquared = horizontalDistanceSquared(position, goal)
        return routeProgress.hasStalled(
            job.npcUuid,
            job.phase,
            position,
            goal,
            semanticPathStallTimeout(distanceSquared),
            snapshot.gameTime,
        )
    }

    private fun movementGoal(job: LumberjackDemoJob): NpcPosition? = when (job.phase) {
        LumberjackDemoPhase.TRAVEL_TO_CHEST,
        LumberjackDemoPhase.RETURN_TO_CHEST,
        -> blockNavigationPosition(job.chestApproach ?: job.chestPosition)

        LumberjackDemoPhase.TRAVEL_TO_LOG -> job.miningStance?.let(::blockNavigationPosition)
        LumberjackDemoPhase.CLIMB_TRUNK -> job.trunkBasePosition
            ?.let { trunkBase -> blockNavigationPosition(trunkLaunchCell(trunkBase, job.miningStance)) }

        // Collection can select a nearby falling item each tick. It has its own bounded search
        // and must not be measured against the former block cell as though that were a route.
        LumberjackDemoPhase.COLLECT_LOG_DROP -> null

        else -> null
    }

    private fun semanticPathStallTimeout(horizontalDistanceSquared: Double): Int {
        val horizontalDistance = kotlin.math.sqrt(horizontalDistanceSquared)
        val expectedTicks = kotlin.math.ceil(horizontalDistance / EXPECTED_NAVIGATION_BLOCKS_PER_TICK).toInt()
        return (PATH_REPLAN_GRACE_TICKS + expectedTicks * PATH_REPLAN_TOLERANCE_MULTIPLIER)
            .coerceIn(MIN_PATH_REPLAN_TICKS, MAX_PATH_REPLAN_TICKS)
    }

    /**
     * Foliage has a shorter recovery than general route failure: after a brief absence of true
     * horizontal progress, clear the first feet/head leaf on the selected route axes. The
     * ordinary longer watchdog still handles non-foliage geometry and alternate stances.
     */
    private fun clearFoliageForSlowRoute(
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult? {
        if (job.phase in CHEST_TRAVEL_PHASES) {
            val stalled = routeProgress.status(job.npcUuid)?.noProgressTicks ?: 0
            if (stalled < FOLIAGE_ROUTE_PROBE_DELAY_TICKS) {
                val snapshot = npc.snapshot()
                val feet = snapshot.position
                val eye = snapshot.eyePosition
                val feetCell = NpcBlockPosition(floor(feet.x).toInt(), floor(feet.y).toInt(), floor(feet.z).toInt())
                val eyeCell = NpcBlockPosition(floor(eye.x).toInt(), floor(eye.y).toInt(), floor(eye.z).toInt())
                // A trunk already intersecting the body is a confirmed obstruction. Do not
                // let navigation jump deeper into it while waiting for the route watchdog.
                if (world.observeBlock(feetCell)?.isLumberjackWoodLog() != true && world.observeBlock(eyeCell)?.isLumberjackWoodLog() != true) return null
            }
            val destination = blockNavigationPosition(job.chestApproach ?: job.chestPosition)
            val obstruction = foliageObstacleOnNavigationAxes(npc, destination, world, visibleOnly = true, includeLogs = true) ?: return null
            val access = LumberjackChestTravel.beginAccess(job, obstruction)
            routeProgress.clear(job.npcUuid)

            return access
        }
        if (job.phase != LumberjackDemoPhase.TRAVEL_TO_LOG) {
            return null
        }
        // This target is already the one leaf selected for removal. Re-queuing it before
        // `travelToLog` can reach BREAK_LOG resets its stance every eight ticks forever: the
        // exact self-loop seen in a real dense-forest run. The short route probe is for finding
        // a new obstruction on the way to work, never for rediscovering the active work item.
        if (isActiveDeferredFoliageTarget(job, world)) {
            return null
        }
        val watchdog = routeProgress.status(job.npcUuid)
            ?.takeIf { it.noProgressTicks >= FOLIAGE_ROUTE_PROBE_DELAY_TICKS }
            ?: return null
        val stance = job.miningStance ?: return null
        val obstruction = foliageObstacleOnNavigationAxes(npc, blockNavigationPosition(stance), world)
            ?: return null
        if (!deferAccessObstacle(job, obstruction)) {
            return null
        }
        routeProgress.clear(job.npcUuid)
        return NpcActionResult.running("a leaf blocked the route for ${watchdog.noProgressTicks} ticks; clearing that horizontal collision cell")
    }

    private fun recoverFromStalledMovement(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        npc.stopControl()
        routeProgress.clear(job.npcUuid)
        return when (job.phase) {
            LumberjackDemoPhase.TRAVEL_TO_LOG, LumberjackDemoPhase.CLIMB_TRUNK -> {
                // A leaf is a real, already-selected work target here. Do not defer the same
                // target again from the long route watchdog; give the ordinary stance/break
                // path bounded retries, then move on rather than watching one trunk forever.
                if (job.phase == LumberjackDemoPhase.TRAVEL_TO_LOG && isActiveDeferredFoliageTarget(job, world)) {
                    return continueAfterUnreachableWorkBlock(world, job)
                }
                // First inspect the feet/head cells on the exact route axes. This is deliberately
                // local: it clears a collision corridor, never scans a whole canopy or a distant
                // vertical ray that could leave the NPC staring into the sky.
                val suppliedLog = job.targetPosition?.takeIf { target -> world.observeBlock(target)?.isLumberjackWoodLog() == true }
                val axisObstruction = job.miningStance?.let { stance ->
                    foliageObstacleOnNavigationAxes(npc, blockNavigationPosition(stance), world)
                }
                val routeObstruction = job.miningStance?.let { stance ->
                    accessObstacleOnRay(npc, blockNavigationPosition(stance), null, world)
                }
                val viewObstruction = suppliedLog?.let { log -> accessObstacleBlockingViewOf(npc, log, world) }
                val obstruction = axisObstruction ?: routeObstruction ?: viewObstruction
                if (obstruction != null && deferAccessObstacle(job, obstruction)) {
                    return NpcActionResult.running("a nearby leaf blocks the selected collision corridor; clearing it before choosing another route")
                }
                job.miningStance?.let { failedStance -> rememberRejectedStance(job, failedStance) }
                val trunkBase = job.trunkBasePosition
                val alternate = trunkBase?.let { base ->
                    selectMiningStance(npc.snapshot().position, base, world, rejectedStances(job))
                }
                if (alternate != null) {
                    job.miningStance = alternate
                    job.climbJumpAttempts = 0
                    job.climbForwardTicks = 0
                    job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

                    NpcActionResult.running("the route made no horizontal progress; trying another clear mining side")
                } else {
                    if (job.phase == LumberjackDemoPhase.CLIMB_TRUNK) {
                        beginPillarOrContinue(npc, world, job)
                    } else {
                        continueAfterUnreachableWorkBlock(world, job)
                    }
                }
            }
            LumberjackDemoPhase.COLLECT_LOG_DROP -> continueAfterUnreachableWorkBlock(world, job)
            LumberjackDemoPhase.TRAVEL_TO_CHEST, LumberjackDemoPhase.RETURN_TO_CHEST -> finish(
                server,
                npc,
                job,
                NpcActionResult.failed("NPC made no horizontal progress before its bounded path-replan deadline", NpcActionCode.WORLD_REJECTED),
            )
            else -> NpcActionResult.running("no movement recovery is needed for ${job.phase}")
        }
    }

    /**
     * Defers one supplied log while a local access leaf is removed. Repeated leaf layers retain
     * the original log as the destination, so each cleared path cell resumes the real task
     * rather than building an unbounded leaf-target chain.
     */
    private fun deferAccessObstacle(job: LumberjackDemoJob, obstacle: NpcBlockPosition): Boolean {
        val suppliedLog = job.accessReturnTarget ?: job.targetPosition ?: return false
        if (obstacle == suppliedLog || !leafInSameBoundedTreeVolume(suppliedLog, obstacle)) {
            return false
        }
        job.accessReturnTarget = suppliedLog
        job.targetPosition = obstacle
        job.miningStance = null
        // A world change may make a previously bad side valid. The leaf itself is the only
        // deliberate geometry change in this branch, so it is safe to reconsider the four
        // bounded stances after it has been removed.
        job.rejectedMiningStances.clear()
        job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

        LOGGER.info("Lumberjack clears foliage npc={} obstacle={} deferredLog={}", job.npcUuid, obstacle, suppliedLog)
        return true
    }

    private fun isActiveDeferredFoliageTarget(job: LumberjackDemoJob, world: NpcWorldView): Boolean {
        if (job.accessReturnTarget == null) {
            return false
        }
        val target = job.targetPosition ?: return false
        return world.isLeafOrSupportedSnowObstacle(target)
    }

    private fun trunkLaunchCell(trunkBase: NpcBlockPosition, stance: NpcBlockPosition?): NpcBlockPosition = when {
        stance == null || stance.x < trunkBase.x -> NpcBlockPosition(trunkBase.x - 1, trunkBase.y, trunkBase.z)
        stance.x > trunkBase.x -> NpcBlockPosition(trunkBase.x + 1, trunkBase.y, trunkBase.z)
        stance.z < trunkBase.z -> NpcBlockPosition(trunkBase.x, trunkBase.y, trunkBase.z - 1)
        else -> NpcBlockPosition(trunkBase.x, trunkBase.y, trunkBase.z + 1)
    }

    private fun collectLogDrop(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        // Cutting continues vertically; only recovery may interrupt it for ground drops.
        if (!job.scaffoldMaterialRecovery || job.targetPosition != job.trunkBasePosition) {
            return advanceTrunkPlan(world, job)
        }
        return collectTreeDrops(npc, world, job, recoveringMaterial = true)
    }

    private fun collectTreeDrops(
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
        recoveringMaterial: Boolean = false,
    ): NpcActionResult {
        val collection = treeWorkClaims.collectionFilter(npc.npcUuid, job.workTaskId, job.dimensionId, npc.snapshot().gameTime)
        val progress = LumberjackDropCollector.tick(npc, world, job, collection)

        return when (progress) {
            is LumberjackDropCollector.Result.Running -> progress.action
            is LumberjackDropCollector.Result.Complete -> {
                if (progress.timedOut && progress.remainingDrops > 0) {
                    LOGGER.warn("Lumberjack collection deadline npc={} tree={} remainingDrops={} recovery={}",
                        job.npcUuid, job.trunkBasePosition, progress.remainingDrops, recoveringMaterial)
                }
                if (recoveringMaterial) {
                    resumeWithRecoveredScaffoldMaterial(npc, world, job)
                } else {
                    resumeLogScan(job)
                }
            }
        }
    }

    private fun beginFinalTreeCollection(job: LumberjackDemoJob): NpcActionResult {
        job.scaffoldMaterialRecovery = false
        job.phase = LumberjackDemoPhase.COLLECT_TREE_DROPS
        job.pickupTicks = 0
        job.pickupQuietTicks = 0

        return NpcActionResult.running("trunk and scaffold are finished; collecting settled drops before scanning or depositing")
    }

    private fun returnToChest(server: MinecraftServer, npc: NpcFacade, world: NpcWorldView, job: LumberjackDemoJob): NpcActionResult {
        when (val progress = LumberjackChestTravel.move(npc, world, job)) {
            MoveTowardProgress.ARRIVED -> Unit
            MoveTowardProgress.MOVING -> return NpcActionResult.running("returning wood to the selected chest")
            is MoveTowardProgress.FAILED -> return finish(server, npc, job, progress.result)
        }
        npc.stopControl()
        job.phase = LumberjackDemoPhase.DEPOSIT_WOOD

        return NpcActionResult.running("arrived at the selected chest with the gathered wood")
    }

    /** The demo deposits task wood before all slots are occupied, then resumes the exact job. */
    private fun shouldReturnWoodForCapacity(npc: NpcFacade, job: LumberjackDemoJob): Boolean {
        if (job.phase !in CAPACITY_RETURN_PHASES || job.resumeWorkAfterDeposit) {
            return false
        }
        if (npc.inventoryContents().count { entry -> !entry.stack.isEmpty } * PERCENT_SCALE < INVENTORY_SIZE * CAPACITY_RETURN_PERCENT) {
            return false
        }
        return InventoryBaselineKernel.excessStacks(npc, job.initialWoodCounts, ::isWoodItem).isNotEmpty()
    }

    private fun beginCapacityWoodReturn(job: LumberjackDemoJob): NpcActionResult {
        job.resumeWorkAfterDeposit = true
        job.phase = LumberjackDemoPhase.RETURN_TO_CHEST

        return NpcActionResult.running("inventory reached $CAPACITY_RETURN_PERCENT% occupied slots; returning gathered wood before continuing the bounded work")
    }

    private fun depositWood(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val chest = world.observeBlockContainer(job.chestPosition)
            ?: return finish(server, npc, job, NpcActionResult.failed("selected chest is no longer a readable nearby container", NpcActionCode.NOT_FOUND))
        val deposit = InventoryBaselineKernel.excessStacks(npc, job.initialWoodCounts) { id -> job.workSelection?.wood?.matches(id) ?: isWoodItem(id) }.firstOrNull()
            ?: return if (job.resumeWorkAfterDeposit) {
                resumeAfterCapacityWoodDeposit(world, job)
            } else {
                finish(server, npc, job, NpcActionResult.succeeded("lumberjack demo complete; gathered wood was returned to the chest"))
            }
        val destination = chest.destinationFor(deposit.itemId)
            ?: return finish(
                server,
                npc,
                job,
                NpcActionResult.failed("selected chest has no room for gathered ${deposit.itemId}; wood remains in NPC inventory", NpcActionCode.MISSING_RESOURCE),
            )
        return finishIfMechanicalFailure(
            server,
            npc,
            job,
            npc.moveInventoryToBlockContainer(
                deposit.slot,
                NpcBlockContainerSlot(job.chestPosition, destination),
                deposit.count,
            ),
        )
    }

    private fun resumeAfterCapacityWoodDeposit(
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        job.resumeWorkAfterDeposit = false
        val target = job.targetPosition
        if (target != null && world.observeBlock(target)?.isLumberjackWorkBlock() == true) {
            job.miningStance = null
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

            return NpcActionResult.running("capacity chest run is complete; returning to the saved tree task")
        }
        if (job.trunkBasePosition != null) {
            return advanceTrunkPlan(world, job)
        }
        job.phase = LumberjackDemoPhase.SEARCH_WOOD

        return NpcActionResult.running("capacity chest run is complete; resuming the bounded wood scan")
    }

    private fun beginLogCollection(job: LumberjackDemoJob): NpcActionResult {
        trackedBlockBreaks.clear(job.npcUuid)
        job.failedWorkAttempts = 0
        job.phase = LumberjackDemoPhase.COLLECT_LOG_DROP
        job.pickupTicks = 0
        job.pickupQuietTicks = 0

        return if (job.scaffoldMaterialRecovery) {
            NpcActionResult.running("the retained stump was felled; collecting its real wood drops for the emergency scaffold")
        } else {
            NpcActionResult.running("wood block is gone; resolving the remaining trunk plan")
        }
    }

    /**
     * The base is intentionally preserved during normal climbing. Only when ordinary tagged
     * scaffold material is absent do we fell it, let the already-cut trunk drops reach ground,
     * collect them normally, and reuse that exact wood as a bounded fallback.
     */
    private fun beginScaffoldMaterialRecovery(
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult? {
        val target = job.targetPosition ?: return null
        val trunkBase = job.trunkBasePosition ?: return null
        if (target == trunkBase) {
            return null
        }
        if (job.scaffoldRecoveryAttempts >= MAX_SCAFFOLD_RECOVERY_ATTEMPTS) {
            LOGGER.warn("Lumberjack scaffold recovery exhausted npc={} tree={} target={}", job.npcUuid, trunkBase, target)
            return null
        }
        job.scaffoldRecoveryAttempts += 1
        job.pickupQuietTicks = 0
        if (world.observeBlock(trunkBase)?.isLumberjackWoodLog() != true) {
            // This is the second and later wood-only elevation of the same tree. The retained
            // stump has already been felled, so do not reset the whole plan merely because the
            // next short pillar needs one more recovered log. Reuse the same bounded pickup
            // phase; it observes only task drops inside this trunk's local volume.
            job.scaffoldMaterialRecovery = true
            job.blockedLogPosition = target
            job.targetPosition = trunkBase
            job.miningStance = null
            job.pickupTicks = 0
            job.phase = LumberjackDemoPhase.COLLECT_LOG_DROP

            return NpcActionResult.running("the retained stump is already gone; gathering nearby recovered scaffold wood")
        }
        job.blockedLogPosition = target
        job.targetPosition = trunkBase
        job.miningStance = null
        job.scaffoldMaterialRecovery = true
        job.scaffoldMaterialRecoveryPending = false
        job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

        return NpcActionResult.running("no cheap scaffold block is carried; felling the retained stump to recover already-cut wood")
    }

    private fun resumeWithRecoveredScaffoldMaterial(
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val deferredLog = job.blockedLogPosition ?: return resumeLogScan(job)
        job.targetPosition = deferredLog
        job.blockedLogPosition = null
        job.miningStance = null
        job.rejectedMiningStances.clear()

        // The elevation kernel can re-center a grounded body over adjacent real support.
        // Returning to a distant old stance can be impossible on a narrow cleared trunk ledge.
        return beginPillarOrContinue(npc, world, job)
    }

    private fun continueAfterCompletedWorkBlock(npc: NpcFacade, job: LumberjackDemoJob): NpcActionResult = when {
        job.accessReturnTarget != null -> resumeAccessWork(npc, job)
        job.scaffoldMaterialRecovery && job.targetPosition == job.trunkBasePosition -> beginLogCollection(job)
        job.blockedLogPosition != null -> resumeBlockedLog(npc, job)
        else -> beginLogCollection(job)
    }

    /** Continue with the exact upper log deferred by a leaf or a required stepping block. */
    private fun resumeBlockedLog(npc: NpcFacade, job: LumberjackDemoJob): NpcActionResult {
        val blockedLog = job.blockedLogPosition ?: return resumeLogScan(job)
        job.targetPosition = blockedLog
        job.blockedLogPosition = null
        job.miningStance = job.recordedScaffoldStance(npc.snapshot())
        job.phase = if (job.miningStance != null) LumberjackDemoPhase.BREAK_LOG else LumberjackDemoPhase.TRAVEL_TO_LOG

        LOGGER.info("Lumberjack foliage path cleared npc={} resumingLog={}", job.npcUuid, blockedLog)
        return NpcActionResult.running("temporary trunk obstruction cleared; resuming the same supplied upper log")
    }

    private fun resumeAccessWork(npc: NpcFacade, job: LumberjackDemoJob): NpcActionResult {
        val target = job.accessReturnTarget ?: return resumeLogScan(job)
        job.targetPosition = target
        job.accessReturnTarget = null
        job.miningStance = job.recordedScaffoldStance(npc.snapshot())
        job.phase = if (job.miningStance != null) LumberjackDemoPhase.BREAK_LOG else LumberjackDemoPhase.TRAVEL_TO_LOG

        LOGGER.info("Lumberjack access cleared npc={} resuming={} materialTarget={} scaffoldStance={}", job.npcUuid, target, job.blockedLogPosition, job.miningStance)
        return NpcActionResult.running("access obstruction cleared; resuming the exact suspended work without leaving its scaffold")
    }

    /** The demo climbs and clears one claimed 1x1 trunk; leaves are only access obstructions. */
    private fun advanceTrunkPlan(world: NpcWorldView, job: LumberjackDemoJob): NpcActionResult {
        val trunkBase = job.trunkBasePosition ?: return resumeLogScan(job)
        val completed = job.targetPosition ?: return resumeLogScan(job)
        val supports = job.pillarSession?.placedPositions.orEmpty()
        val above = NpcBlockPosition(trunkBase.x, completed.y + 1, trunkBase.z)
        if (above !in supports && world.observeBlock(above)?.isLumberjackWoodLog() == true) {
            job.targetPosition = above
            job.pickupTicks = 0
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

            return NpcActionResult.running("continuing upward through the supplied trunk")
        }
        // The base remains as an intentional, player-like one-block step until every higher log
        // is gone. Foliage does not become a separate cleanup task for this demo.
        val retainedUpperLog = highestRemainingLumberjackTrunkLog(world, trunkBase, supports)
            ?.takeIf { candidate -> candidate != trunkBase }
        if (retainedUpperLog != null) {
            job.targetPosition = retainedUpperLog
            job.pickupTicks = 0
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

            return NpcActionResult.running("finishing the remaining reachable trunk")
        }
        return finishRetainedTrunk(world, job, trunkBase)
    }

    private fun finishRetainedTrunk(
        world: NpcWorldView,
        job: LumberjackDemoJob,
        trunkBase: NpcBlockPosition,
    ): NpcActionResult {
        val scaffold = job.pillarSession
        val retainedLowerLog = highestRemainingLumberjackTrunkLog(world, trunkBase, scaffold?.placedPositions.orEmpty())
        if (retainedLowerLog != null) {
            job.targetPosition = retainedLowerLog
            job.pickupTicks = 0
            if (scaffold != null && scaffold.placedPositions.isNotEmpty()) {
                // The retained stump can be underneath our scaffold. Walking around it to
                // obtain a mining ray abandons the only safe top-down cleanup stance.
                // Descend first, then resume this exact lower log from the real ground.
                TemporaryPillarKernel.beginCleanup(scaffold)
                job.phase = LumberjackDemoPhase.PILLAR_CLEANUP

                return NpcActionResult.running("upper trunk complete; dismantling the scaffold before its retained foundation")
            }
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

            return NpcActionResult.running("upper trunk complete; finishing the retained lower trunk")
        }
        if (scaffold != null) {
            TemporaryPillarKernel.beginCleanup(scaffold)
            job.phase = LumberjackDemoPhase.PILLAR_CLEANUP

            return NpcActionResult.running("trunk is finished; dismantling its recorded scaffold before final collection")
        }
        return beginFinalTreeCollection(job)
    }

    /**
     * An unreachable supplied block must not poison the whole bounded scan. A ray-confirmed leaf
     * is only ever an immediate, temporary obstruction; otherwise this retries the same target
     * from a fresh stance before giving up this tree.
     */
    private fun continueAfterUnreachableWorkBlock(
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val retryTarget = job.targetPosition
            ?.takeIf { target -> world.observeBlock(target)?.isLumberjackWorkBlock() == true }
        if (retryTarget != null && job.failedWorkAttempts < MAX_FAILED_WORK_ATTEMPTS) {
            job.failedWorkAttempts += 1
            job.miningStance = null
            job.climbJumpAttempts = 0
            job.climbForwardTicks = 0
            job.scaffoldMaterialRecovery = false
            job.scaffoldMaterialRecoveryPending = false
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG

            return NpcActionResult.running(
                "Core rejected the supplied work block; retrying it from a fresh stance (${job.failedWorkAttempts}/$MAX_FAILED_WORK_ATTEMPTS)",
            )
        }
        return resumeLogScan(job)
    }

    /**
     * This is deliberately reached only after the supplied log has failed normal reach, an
     * existing stump, a lower trunk step and a clear alternate side. Core receives no request
     * other than the ordinary player-like primitive calls issued by [TemporaryPillarKernel].
     */
    private fun beginPillarOrContinue(
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val target = job.targetPosition ?: return resumeLogScan(job)
        val physical = npc.snapshot()
        if (!physical.onGround && !physical.inWater && !physical.inLava && !physical.climbing && !physical.riding) {
            // A normal step/jump can leave the body airborne for a few ticks. Do not consume a
            // bounded retry or start another task while its survival physics are unresolved.
            job.phase = LumberjackDemoPhase.PILLAR_UP

            return NpcActionResult.running("the supplied log still needs elevation; waiting to stabilize before evaluating the pillar base")
        }
        when (val begun = TemporaryPillarKernel.begin(npc, world, target)) {
            is TemporaryPillarKernel.PillarBeginResult.Started -> {
                job.pillarSession = begun.session
                job.phase = LumberjackDemoPhase.PILLAR_UP

                LOGGER.info("Lumberjack pillar started npc={} target={} estimate={} material={} slot={}", job.npcUuid, target, begun.session.estimatedLevels, begun.session.materialItemId, begun.session.materialOriginalSlot)
                return NpcActionResult.running("the supplied log remains unreachable after natural geometry; starting one-level player-like elevation")
            }
            is TemporaryPillarKernel.PillarBeginResult.BlockedByLeaf -> {
                if (deferAccessObstacle(job, begun.position)) {
                    return NpcActionResult.running("one leaf or its snow layer physically blocks the jump headroom; clearing only that obstruction")
                }
                return continueAfterUnreachableWorkBlock(world, job)
            }
            is TemporaryPillarKernel.PillarBeginResult.Failed -> {
                LOGGER.info("Lumberjack pillar did not start npc={} target={} code={} position={}", job.npcUuid, target, begun.code, physical.position)
                if (begun.code in SCAFFOLD_MATERIAL_FAILURES) {
                    LOGGER.info(
                        "Lumberjack scaffold material inventory npc={} target={} stacks={}",
                        job.npcUuid,
                        target,
                        npc.inventoryContents()
                            .filter { entry -> !entry.stack.isEmpty && TemporaryPillarKernel.isPermittedMaterial(entry.knowledge) }
                            .joinToString(prefix = "[", postfix = "]") { entry -> "${entry.stack.itemId}x${entry.stack.count}@${entry.slot}" },
                    )
                    beginScaffoldMaterialRecovery(world, job)?.let { recovery -> return recovery }
                }
                return continueAfterUnreachableWorkBlock(world, job)
            }
        }
    }

    private fun advancePillar(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val session = job.pillarSession
        if (session == null) {
            val physical = npc.snapshot()
            return if (!physical.onGround && !physical.inWater && !physical.inLava && !physical.climbing && !physical.riding) {
                NpcActionResult.running("waiting for stable footing before beginning temporary elevation")
            } else {
                beginPillarOrContinue(npc, world, job)
            }
        }
        return when (val progress = TemporaryPillarKernel.tick(npc, world, session)) {
            is TemporaryPillarKernel.PillarProgress.Running -> {

                NpcActionResult.running(progress.detail)
            }
            TemporaryPillarKernel.PillarProgress.TargetReached -> {
                val feet = npc.snapshot().position.let { position ->
                    NpcBlockPosition(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())
                }
                // The elevated feet cell is a real, already-reached work stance. Keeping it
                // prevents navigation from dragging the NPC down just before the next upper log.
                job.miningStance = feet
                job.phase = LumberjackDemoPhase.BREAK_LOG
                job.failedWorkAttempts = 0

                NpcActionResult.running("temporary elevation made the supplied log reachable; resuming normal chopping")
            }
            TemporaryPillarKernel.PillarProgress.TargetGone -> {
                TemporaryPillarKernel.beginCleanup(session)
                job.phase = LumberjackDemoPhase.PILLAR_CLEANUP

                NpcActionResult.running("the elevated target disappeared; descending the recorded temporary scaffold")
            }
            is TemporaryPillarKernel.PillarProgress.BlockedByLeaf -> {
                // The leaf is only a temporary headroom obstruction. Keep ownership of every
                // already placed scaffold block so the deferred log can reuse it and later
                // cleanup cannot accidentally abandon player-built world state.
                if (deferAccessObstacle(job, progress.position)) {
                    return NpcActionResult.running("one leaf or its snow layer blocks the jump headroom; clearing it before resuming elevation")
                }
                continueAfterUnreachableWorkBlock(world, job)
            }
            is TemporaryPillarKernel.PillarProgress.Failed -> {
                LOGGER.info("Lumberjack pillar stopped npc={} target={} code={} detail={}", job.npcUuid, job.targetPosition, progress.code, progress.detail)
                if (progress.code == TemporaryPillarResultCode.PILLAR_EFFECT_UNCERTAIN)
                    return finish(server, npc, job, NpcActionResult.failed(progress.detail, NpcActionCode.EFFECT_UNCERTAIN))
                if (progress.code == TemporaryPillarResultCode.PILLAR_TARGET_OBSTRUCTED) LumberjackDeferredWork.record(job, world, progress.obstruction)
                // A geometry failure does not become solvable by immediately rebuilding against
                // the same trunk. Material exhaustion is different: the wood-only fallback must
                // descend, collect the real drops, and then continue from its bounded recovery.
                if (progress.code in SCAFFOLD_MATERIAL_FAILURES) {
                    // Inventory depletion is expected while a wood-only fallback turns real
                    // drops into its next short scaffold. It is not a failed work route.
                    job.abandonTreeAfterPillarCleanup = false
                    if (!job.scaffoldMaterialRecovery) {
                        job.scaffoldMaterialRecoveryPending = true
                    }
                } else {
                    job.failedWorkAttempts = (job.failedWorkAttempts + 1).coerceAtMost(MAX_FAILED_WORK_ATTEMPTS)
                    // Returning from a bounded external interruption can require dismantling
                    // a step that was reachable before the body left. Re-observe this same tree
                    // afterwards; repeated failed recovery still spends the existing retry cap.
                    job.abandonTreeAfterPillarCleanup = progress.code != TemporaryPillarResultCode.PILLAR_INTERRUPTED ||
                        job.failedWorkAttempts >= MAX_FAILED_WORK_ATTEMPTS
                }
                if (session.placedPositions.isNotEmpty()) {
                    // A failed next level must never erase ownership of the honest levels below
                    // it. Cleanup itself waits for normal landing if this failure occurred while
                    // airborne, then removes only the positions recorded by this session.
                    TemporaryPillarKernel.beginCleanup(session)
                    job.phase = LumberjackDemoPhase.PILLAR_CLEANUP

                    return NpcActionResult.running("pillar stopped after placing temporary blocks; resolving the recorded scaffold safely")
                }
                job.pillarSession = null
                resumeLogScan(job)
            }
            TemporaryPillarKernel.PillarProgress.CleanupComplete, TemporaryPillarKernel.PillarProgress.CleanupIncomplete ->
                finish(server, npc, job, NpcActionResult.failed("unexpected cleanup result in active pillar phase", NpcActionCode.WORLD_REJECTED))
        }
    }

    private fun cleanPillar(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val session = job.pillarSession ?: run {
            job.phase = LumberjackDemoPhase.RETURN_TO_CHEST

            return NpcActionResult.running("no temporary scaffold remains; returning to the chest")
        }
        return when (val progress = TemporaryPillarKernel.tickCleanup(npc, world, session)) {
            is TemporaryPillarKernel.PillarProgress.Running -> {

                NpcActionResult.running(progress.detail)
            }
            TemporaryPillarKernel.PillarProgress.CleanupComplete -> {
                job.pillarSession = null
                resumeAfterPillarCleanup(npc, world, job)

                LOGGER.info("Lumberjack scaffold cleanup handoff npc={} position={} target={}", job.npcUuid, npc.snapshot().position, job.targetPosition)
                NpcActionResult.running("temporary scaffold was removed with normal block breaks; resuming the supplied task")
            }
            TemporaryPillarKernel.PillarProgress.CleanupIncomplete -> {
                val preserved = LumberjackCleanupReport.preserve(server, job, world, session.lastResult.name)
                if (preserved.status != NpcActionStatus.SUCCEEDED) {
                    npc.stopControl()
                    npc.abortBlockBreak()
                    return preserved
                }
                LOGGER.warn("Lumberjack left temporary scaffold npc={} positions={} reason={}", job.npcUuid, session.placedPositions, session.lastResult)
                job.pillarSession = null
                resumeAfterPillarCleanup(npc, world, job)

                NpcActionResult.running("temporary scaffold cleanup is incomplete; no unrelated blocks were touched")
            }
            else -> {
                val preserved = LumberjackCleanupReport.preserve(server, job, world, session.lastResult.name)
                if (preserved.status != NpcActionStatus.SUCCEEDED) {
                    npc.stopControl()
                    npc.abortBlockBreak()
                    return preserved
                }
                LOGGER.warn("Lumberjack scaffold cleanup transitioned unexpectedly npc={} progress={} state={} positions={}", job.npcUuid, progress, session.state, session.placedPositions)
                job.pillarSession = null
                resumeAfterPillarCleanup(npc, world, job)

                NpcActionResult.running("temporary scaffold no longer has a safe cleanup path")
            }
        }
    }

    private fun resumeAfterPillarCleanup(npc: NpcFacade, world: NpcWorldView, job: LumberjackDemoJob) {
        if (job.abandonTreeAfterPillarCleanup) {
            LOGGER.info("Lumberjack yielded tree after pillar cleanup npc={} target={} failedAttempts={}", job.npcUuid, job.targetPosition, job.failedWorkAttempts)
            resumeLogScan(job)
            return
        }
        if (job.scaffoldMaterialRecoveryPending) {
            job.scaffoldMaterialRecoveryPending = false
            beginScaffoldMaterialRecovery(world, job)?.let {
                LOGGER.info("Lumberjack begins post-cleanup wood recovery npc={} target={}", job.npcUuid, job.targetPosition)
                return
            }
        }
        val target = job.targetPosition
        if (target != null && world.observeBlock(target)?.isLumberjackWorkBlock() == true) {
            val trunkBase = job.trunkBasePosition
            if (job.scaffoldMaterialRecovery && trunkBase != null && target != trunkBase) {
                // A wood-only session consumed its one verified stack. It is now safely back on
                // the ground, where both the just-cut log and the dismantled scaffold have real
                // item entities. Gather those first rather than pretending the empty stack can
                // continue a taller pillar.
                // Every full cleanup/recovery cycle spends the same persisted budget. The old
                // direct transition skipped its guard and rebuilt an unchanged failed pillar.
                if (beginScaffoldMaterialRecovery(world, job) == null) {
                    beginFinalTreeCollection(job)
                }
                return
            }
            val snapshot = npc.snapshot()
            // Descent can end on the retained stump itself. It is already a valid place to
            // mine that last log; forcing a lower side route can strand the job on a ledge.
            job.miningStance = if (trunkBase != null && isStandingOnPreservedStump(snapshot.position, snapshot.onGround, trunkBase)) {
                NpcBlockPosition(trunkBase.x, trunkBase.y + 1, trunkBase.z)
            } else {
                null
            }
            job.phase = if (job.miningStance != null) LumberjackDemoPhase.BREAK_LOG else LumberjackDemoPhase.TRAVEL_TO_LOG
            LOGGER.info("Lumberjack scaffold cleanup complete npc={} target={} nextPhase={} stance={}", job.npcUuid, target, job.phase, job.miningStance)
        } else {
            if (job.trunkBasePosition != null) {
                advanceTrunkPlan(world, job)
            } else {
                job.phase = LumberjackDemoPhase.RETURN_TO_CHEST
            }
            LOGGER.info("Lumberjack scaffold cleanup complete npc={} target={} nextPhase={}", job.npcUuid, target, job.phase)
        }
    }

    private fun resumeLogScan(job: LumberjackDemoJob): NpcActionResult {
        val scaffold = job.pillarSession
        if (scaffold != null && scaffold.placedPositions.isNotEmpty()) {
            job.abandonTreeAfterPillarCleanup = true
            TemporaryPillarKernel.beginCleanup(scaffold)
            job.phase = LumberjackDemoPhase.PILLAR_CLEANUP

            return NpcActionResult.running("resolving this tree's scaffold before another tree can be selected")
        }
        clearTreePlan(job)
        job.phase = LumberjackDemoPhase.SEARCH_WOOD

        return NpcActionResult.running("continuing the bounded wood scan")
    }

    private fun clearTreePlan(job: LumberjackDemoJob) {
        treeWorkClaims.release(job.npcUuid)
        neighborRepulsion.clear(job.npcUuid)
        job.targetPosition = null
        job.trunkBasePosition = null
        job.blockedLogPosition = null
        job.miningStance = null
        job.scaffoldMaterialRecovery = false
        job.accessReturnTarget = null
        job.scaffoldMaterialRecoveryPending = false
        job.rejectedMiningStances.clear()
        job.initialTrunkTargetPending = false
        job.climbJumpAttempts = 0
        job.climbForwardTicks = 0
        job.failedWorkAttempts = 0
        job.abandonTreeAfterPillarCleanup = false
        job.pickupTicks = 0
        job.pickupQuietTicks = 0
        job.scaffoldRecoveryAttempts = 0
    }

    private fun NpcActionResult.isFailure(): Boolean = status == NpcActionStatus.REJECTED ||
        status == NpcActionStatus.FAILED || status == NpcActionStatus.UNSUPPORTED

    private fun finishIfMechanicalFailure(
        server: MinecraftServer,
        npc: NpcFacade,
        job: LumberjackDemoJob,
        result: NpcActionResult,
    ): NpcActionResult = when (result.status) {
        NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED -> finish(server, npc, job, result)
        else -> result
    }

    private fun finish(
        server: MinecraftServer,
        npc: NpcFacade,
        job: LumberjackDemoJob,
        result: NpcActionResult,
    ): NpcActionResult {
        npc.stopControl()
        routeProgress.clear(job.npcUuid)
        upwardStareRecovery.clear(job.npcUuid)
        trackedBlockBreaks.clear(job.npcUuid)
        treeWorkClaims.release(job.npcUuid)
        neighborRepulsion.clear(job.npcUuid)
        npc.abortBlockBreak()
        val preserved = LumberjackCleanupReport.preserve(server, job, npc.worldView(), result.code.name)
        if (preserved.status != NpcActionStatus.SUCCEEDED) return preserved
        job.executionFinished = true
        if (result.status == NpcActionStatus.REJECTED || result.status == NpcActionStatus.FAILED || result.status == NpcActionStatus.UNSUPPORTED) {
            LOGGER.warn(
                "Lumberjack demo stopped npc={} phase={} status={} code={}: {}",
                job.npcUuid,
                job.phase,
                result.status,
                result.code,
                result.detail,
            )
        }
        return result
    }

    private fun restorePreviousPacks(server: MinecraftServer, npcUuid: java.util.UUID, job: LumberjackDemoJob) {
        if (BehaviorRuntimeService.assignedPacks(server, npcUuid) == listOf(PACK_ID)) {
            BehaviorRuntimeService.assignPacks(server, npcUuid, job.previousPackIds)
        }
    }

    private fun isWoodItem(itemId: String): Boolean {
        val path = itemId.substringAfter(':', itemId)
        return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae")
    }


    private fun io.samcnpc.core.api.NpcEquipmentKnowledge.itemAt(destination: NpcEquipmentDestination) = when (destination) {
        NpcEquipmentDestination.HEAD -> head
        NpcEquipmentDestination.CHEST -> chest
        NpcEquipmentDestination.LEGS -> legs
        NpcEquipmentDestination.FEET -> feet
        NpcEquipmentDestination.MAIN_HAND -> mainHand
        NpcEquipmentDestination.OFF_HAND -> offHand
    }

    private const val WORK_AREA_SIDE = 50
    private const val WORK_HALF_SIDE = WORK_AREA_SIDE / 2
    private val CHEST_TRAVEL_PHASES = setOf(LumberjackDemoPhase.TRAVEL_TO_CHEST, LumberjackDemoPhase.RETURN_TO_CHEST)
    private const val CLIMB_LAUNCH_ARRIVAL_DISTANCE = 0.55
    // A failed natural stump step is an escalation signal, not permission to bunny-hop at the
    // same collision. The post-launch forward commitment gives this one player-like attempt
    // enough momentum before the policy selects a scaffold.
    private const val CLIMB_POST_JUMP_FORWARD_TICKS = 6
    private const val STUMP_JUMP_CENTER_TOLERANCE = 0.25
    private const val INVENTORY_SIZE = 36
    private const val CAPACITY_RETURN_PERCENT = 70
    private const val PERCENT_SCALE = 100
    private const val PERSONAL_SPACE_QUERY_RADIUS = 3.0
    private const val MAX_PERSONAL_SPACE_OBSERVATIONS = 12
    private const val PERSONAL_SPACE_NAVIGATION_SPEED = 0.75F
    private const val LOG_SCAN_COLUMNS_PER_TICK = 128
    private const val MAX_FAILED_WORK_ATTEMPTS = 3
    private const val MAX_SCAFFOLD_RECOVERY_ATTEMPTS = 3
    private val TRUNK_WORK_PHASES = setOf(LumberjackDemoPhase.TRAVEL_TO_LOG, LumberjackDemoPhase.BREAK_LOG, LumberjackDemoPhase.COLLECT_LOG_DROP)
    private const val EXPECTED_NAVIGATION_BLOCKS_PER_TICK = 0.10
    private const val PATH_REPLAN_GRACE_TICKS = 12
    private const val PATH_REPLAN_TOLERANCE_MULTIPLIER = 2
    private const val MIN_PATH_REPLAN_TICKS = 36
    private const val MAX_PATH_REPLAN_TICKS = 72
    private const val FOLIAGE_ROUTE_PROBE_DELAY_TICKS = 8
    // A tiny sideways/vertical collision jitter must not reset a route watchdog. The body needs
    // to get at least a quarter block closer to its chosen horizontal destination.
    private const val SEMANTIC_PATH_PROGRESS_DISTANCE_SQR = 0.0625
    private val SCAFFOLD_MATERIAL_FAILURES = setOf(
        TemporaryPillarResultCode.PILLAR_NO_MATERIAL,
        TemporaryPillarResultCode.PILLAR_INSUFFICIENT_MATERIAL,
    )
    private val LOGGER = LogUtils.getLogger()
    private val CHEST_BLOCK_IDS = setOf("minecraft:chest", "minecraft:trapped_chest")
    private val CAPACITY_RETURN_PHASES = setOf(
        LumberjackDemoPhase.SEARCH_WOOD,
        LumberjackDemoPhase.TRAVEL_TO_LOG,
        LumberjackDemoPhase.COLLECT_LOG_DROP,
    )
    private val ARMOR_DESTINATIONS = listOf(
        NpcEquipmentDestination.HEAD,
        NpcEquipmentDestination.CHEST,
        NpcEquipmentDestination.LEGS,
        NpcEquipmentDestination.FEET,
    )
    private val CHEST_HEIGHT_OFFSETS = listOf(0, -1, 1, -2, 2, -3, 3, -4, 4)
    private val LOG_HEIGHT_OFFSETS = (-2..24).toList()
    private val CHEST_OFFSETS = orderedSquareOffsets()
    private val WORK_OFFSETS = CHEST_OFFSETS
    private val MOVEMENT_PHASES = setOf(
        LumberjackDemoPhase.TRAVEL_TO_CHEST,
        LumberjackDemoPhase.TRAVEL_TO_LOG,
        LumberjackDemoPhase.CLIMB_TRUNK,
        LumberjackDemoPhase.COLLECT_LOG_DROP,
        LumberjackDemoPhase.RETURN_TO_CHEST,
    )
    private val PERSONAL_SPACE_PHASES = setOf(
        LumberjackDemoPhase.TRAVEL_TO_LOG,
        LumberjackDemoPhase.CLIMB_TRUNK,
    )
    private val UPWARD_STARE_MONITORED_PHASES = setOf(
        LumberjackDemoPhase.TRAVEL_TO_LOG,
        LumberjackDemoPhase.CLIMB_TRUNK,
        LumberjackDemoPhase.BREAK_LOG,
    )

    private fun orderedSquareOffsets(): List<Pair<Int, Int>> =
        (-WORK_HALF_SIDE until WORK_HALF_SIDE)
            .flatMap { x -> (-WORK_HALF_SIDE until WORK_HALF_SIDE).map { z -> x to z } }
            .sortedWith(compareBy<Pair<Int, Int>> { it.first * it.first + it.second * it.second }.thenBy { it.first }.thenBy { it.second })

}
