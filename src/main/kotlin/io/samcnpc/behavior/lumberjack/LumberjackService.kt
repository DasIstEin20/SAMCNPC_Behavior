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
import io.samcnpc.core.api.NpcPosition
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
    private val treeWorkClaims = SpatialWorkClaimKernel()
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

    fun start(server: MinecraftServer, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        if (PACK_ID !in BehaviorRuntimeService.activePackIds()) {
            val reload = BehaviorRuntimeService.reload()
            if (!reload.accepted || PACK_ID !in BehaviorRuntimeService.activePackIds()) {
                return NpcActionResult.rejected(
                    "the built-in lumberjack demo pack is not active: ${reload.messages.joinToString(" | ")}",
                    NpcActionCode.NOT_READY,
                )
            }
        }
        val store = LumberjackDemoStore.forServer(server)
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
        val assignment = BehaviorRuntimeService.assignPacks(server, npc.npcUuid, listOf(PACK_ID))
        if (assignment.status != NpcActionStatus.SUCCEEDED) {
            return assignment
        }
        store.put(
            LumberjackDemoJob(
                npcUuid = npc.npcUuid,
                dimensionId = snapshot.dimensionId,
                chestPosition = chest,
                workCenter = NpcBlockPosition(floor(snapshot.position.x).toInt(), floor(snapshot.position.y).toInt(), floor(snapshot.position.z).toInt()),
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
            ),
        )
        return NpcActionResult.succeeded("lumberjack demo started; chest at ${chest.x}, ${chest.y}, ${chest.z}")
    }

    fun cancel(server: MinecraftServer, npc: NpcFacade): NpcActionResult {
        val store = LumberjackDemoStore.forServer(server)
        val job = store.remove(npc.npcUuid)
            ?: return NpcActionResult.rejected("this NPC has no active lumberjack demo job", NpcActionCode.NOT_READY)
        routeProgress.clear(npc.npcUuid)
        upwardStareRecovery.clear(npc.npcUuid)
        trackedBlockBreaks.clear(npc.npcUuid)
        treeWorkClaims.release(npc.npcUuid)
        neighborRepulsion.clear(npc.npcUuid)
        npc.stopControl()
        restorePreviousPacks(server, npc.npcUuid, job)
        return NpcActionResult.succeeded("lumberjack demo cancelled; prior behavior packs restored")
    }

    /** Compact, command-safe view of a bounded demo job; it contains no world mutation data. */
    fun status(server: MinecraftServer, npcUuid: java.util.UUID): String? {
        val job = LumberjackDemoStore.forServer(server).jobFor(npcUuid) ?: return null
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
            "stance=$stance",
            "pickupTicks=${job.pickupTicks}",
            "jumpAttempts=${job.climbJumpAttempts}",
            "stepForwardTicks=${job.climbForwardTicks}",
            "recoveringWood=${job.scaffoldMaterialRecovery}",
            "recoveryPending=${job.scaffoldMaterialRecoveryPending}",
            "abandonAfterCleanup=${job.abandonTreeAfterPillarCleanup}",
            "stalledTicks=$stalledTicks/$stallLimit",
            "upwardStare=$upwardStare",
            "rejectedStances=${job.rejectedMiningStances}",
            "pillar=$pillar",
        ).joinToString(separator = "; ")
    }

    fun tick(server: MinecraftServer, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val store = LumberjackDemoStore.forServer(server)
        val job = store.jobFor(npc.npcUuid)
            ?: return NpcActionResult.rejected("no active lumberjack demo job", NpcActionCode.NOT_READY)
        val snapshot = npc.snapshot()
        if (snapshot.dimensionId != job.dimensionId) {
            return finish(server, npc, store, job, NpcActionResult.failed("NPC changed dimension during lumberjack demo", NpcActionCode.WORLD_REJECTED))
        }
        val monitorUpwardStare = job.phase in UPWARD_STARE_MONITORED_PHASES
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
                store.markChanged()
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
                return finish(server, npc, store, job, recovery.result)
            }
        }
        maintainTreeClaim(npc, store, job, snapshot)?.let { result -> return result }
        gentlySeparateNearbyLumberjacks(npc, world, store, job, snapshot)?.let { result -> return result }
        if (hasStalledMovement(job, snapshot.position)) {
            return recoverFromStalledMovement(server, npc, world, store, job)
        }
        clearFoliageForSlowRoute(npc, world, store, job)?.let { result -> return result }
        if (shouldReturnWoodForCapacity(npc, job)) {
            return beginCapacityWoodReturn(store, job)
        }
        return when (job.phase) {
            LumberjackDemoPhase.TRAVEL_TO_CHEST -> travelToChest(server, npc, store, job)
            LumberjackDemoPhase.PREPARE_EQUIPMENT -> prepareEquipment(server, npc, world, store, job)
            LumberjackDemoPhase.SEARCH_WOOD -> findNextLog(npc, world, store, job)
            LumberjackDemoPhase.TRAVEL_TO_LOG -> travelToLog(server, npc, world, store, job)
            LumberjackDemoPhase.BREAK_LOG -> breakLog(server, npc, world, store, job)
            LumberjackDemoPhase.CLIMB_TRUNK -> climbTrunk(server, npc, world, store, job)
            LumberjackDemoPhase.COLLECT_LOG_DROP -> collectLogDrop(server, npc, world, store, job)
            LumberjackDemoPhase.RETURN_TO_CHEST -> returnToChest(server, npc, store, job)
            LumberjackDemoPhase.DEPOSIT_WOOD -> depositWood(server, npc, world, store, job)
            LumberjackDemoPhase.PILLAR_UP -> advancePillar(server, npc, world, store, job)
            LumberjackDemoPhase.PILLAR_CLEANUP -> cleanPillar(store, npc, world, job)
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

    private fun travelToChest(server: MinecraftServer, npc: NpcFacade, store: LumberjackDemoStore, job: LumberjackDemoJob): NpcActionResult {
        when (val progress = moveToward(npc, job.chestPosition, CHEST_ARRIVAL_DISTANCE)) {
            MoveTowardProgress.ARRIVED -> Unit
            MoveTowardProgress.MOVING -> return NpcActionResult.running("walking to the selected chest")
            is MoveTowardProgress.FAILED -> return finish(server, npc, store, job, progress.result)
        }
        npc.stopControl()
        job.phase = LumberjackDemoPhase.PREPARE_EQUIPMENT
        store.markChanged()
        return NpcActionResult.running("arrived at the selected chest")
    }

    private fun prepareEquipment(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val chest = world.observeBlockContainer(job.chestPosition)
            ?: return finish(server, npc, store, job, NpcActionResult.failed("selected chest is no longer a readable nearby container", NpcActionCode.NOT_FOUND))
        for (destination in ARMOR_DESTINATIONS) {
            if (!npc.equipmentKnowledge().itemAt(destination).isEmpty) {
                continue
            }
            val inventoryArmor = npc.inventoryContents().firstOrNull { it.knowledge.armorDestination == destination }
            if (inventoryArmor != null) {
                return finishIfMechanicalFailure(server, npc, store, job, npc.equipFromInventory(inventoryArmor.slot, destination))
            }
            val chestArmor = chest.slots.firstOrNull { it.knowledge.armorDestination == destination && !it.stack.isEmpty }
            if (chestArmor != null) {
                return finishIfMechanicalFailure(
                    server,
                    npc,
                    store,
                    job,
                    npc.moveBlockContainerToInventory(NpcBlockContainerSlot(job.chestPosition, chestArmor.slot), 1),
                )
            }
        }
        val carriedAxe = npc.inventoryContents().firstOrNull { it.knowledge.toolKind == NpcToolKind.AXE && !it.stack.isEmpty }
        if (carriedAxe == null) {
            val chestAxe = chest.slots.firstOrNull { it.knowledge.toolKind == NpcToolKind.AXE && !it.stack.isEmpty }
                ?: return finish(server, npc, store, job, NpcActionResult.failed("the selected chest has no axe for the demo", NpcActionCode.MISSING_RESOURCE))
            return finishIfMechanicalFailure(
                server,
                npc,
                store,
                job,
                npc.moveBlockContainerToInventory(NpcBlockContainerSlot(job.chestPosition, chestAxe.slot), 1),
            )
        }
        if (npc.inventoryContents().none { !it.stack.isEmpty && TemporaryPillarKernel.isPrimaryMaterial(it.knowledge) }) {
            // The supplied cheap blocks (dirt/cobblestone/netherrack and data-tagged peers) are
            // the normal scaffold source. Logs stay in the `AVOID` class until this NPC actually
            // fells and collects them as its bounded last-resort recovery material.
            val chestMaterial = chest.slots.firstOrNull { !it.stack.isEmpty && TemporaryPillarKernel.isPrimaryMaterial(it.knowledge) }
            if (chestMaterial != null) {
                return finishIfMechanicalFailure(
                    server,
                    npc,
                    store,
                    job,
                    npc.moveBlockContainerToInventory(NpcBlockContainerSlot(job.chestPosition, chestMaterial.slot), chestMaterial.stack.count),
                )
            }
        }
        // Core deliberately rejects the slow, wrong-tool break of shovel-tagged scaffold. Take
        // an actually supplied shovel before work begins so cleanup is a normal player block
        // break, never a direct world edit. The demo remains usable without one when it never
        // needs a temporary pillar.
        val carriesShovel = npc.inventoryContents().any { it.knowledge.toolKind == NpcToolKind.SHOVEL && !it.stack.isEmpty }
        if (!carriesShovel) {
            val chestShovel = chest.slots.firstOrNull { it.knowledge.toolKind == NpcToolKind.SHOVEL && !it.stack.isEmpty }
            if (chestShovel != null) {
                return finishIfMechanicalFailure(
                    server,
                    npc,
                    store,
                    job,
                    npc.moveBlockContainerToInventory(NpcBlockContainerSlot(job.chestPosition, chestShovel.slot), 1),
                )
            }
        }
        job.phase = LumberjackDemoPhase.SEARCH_WOOD
        store.markChanged()
        return NpcActionResult.running("armor available in the chest has been equipped and an axe is carried")
    }

    private fun findNextLog(npc: NpcFacade, world: NpcWorldView, store: LumberjackDemoStore, job: LumberjackDemoJob): NpcActionResult {
        // The old cursor represented one block in a 50x50x27 volume. At 96 blocks per tick it
        // could make a healthy NPC appear idle for half a minute before it reached a distant
        // column. A cursor now represents one vertical column: the same bounded observation
        // work completes in at most 20 ticks and still never causes Core to select a tree.
        repeat(LOG_SCAN_COLUMNS_PER_TICK) {
            if (job.scanCursor >= WORK_OFFSETS.size) {
                clearTreePlan(job)
                if (job.pillarSession != null) {
                    TemporaryPillarKernel.beginCleanup(job.pillarSession!!)
                    job.phase = LumberjackDemoPhase.PILLAR_CLEANUP
                } else {
                    job.phase = LumberjackDemoPhase.RETURN_TO_CHEST
                }
                store.markChanged()
                return NpcActionResult.running("all 50x50 demo work cells were inspected; resolving any temporary scaffold before returning to the chest")
            }
            val (offsetX, offsetZ) = WORK_OFFSETS[job.scanCursor]
            job.scanCursor += 1
            for (offsetY in LOG_HEIGHT_OFFSETS) {
                val position = NpcBlockPosition(job.workCenter.x + offsetX, job.workCenter.y + offsetY, job.workCenter.z + offsetZ)
                val observation = world.observeBlock(position)
                if (observation != null && observation.isLumberjackWoodLog() && world.isLowestLumberjackWoodLog(position)) {
                    when (val claim = treeWorkClaims.renewOrClaim(npc.npcUuid, job.dimensionId, position, npc.snapshot().gameTime)) {
                        SpatialWorkClaimKernel.Result.Acquired, SpatialWorkClaimKernel.Result.Held -> {
                            job.trunkBasePosition = position
                            job.targetPosition = position
                            job.blockedLogPosition = null
                            job.miningStance = null
                            job.rejectedMiningStances.clear()
                            job.initialTrunkTargetPending = true
                            job.climbJumpAttempts = 0
                            job.climbForwardTicks = 0
                            job.scaffoldMaterialRecovery = false
                            job.scaffoldMaterialRecoveryPending = false
                            job.failedWorkAttempts = 0
                            job.abandonTreeAfterPillarCleanup = false
                            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
                            store.markChanged()
                            return NpcActionResult.running(
                                "planned trunk at ${position.x}, ${position.y}, ${position.z}; approaching it before selecting the eye-height log",
                            )
                        }
                        is SpatialWorkClaimKernel.Result.Contested -> {
                            // Another worker owns this trunk or its immediate scaffold zone. Keep
                            // scanning in the same bounded tick; no second NPC may turn it into a
                            // competing pillar job.
                            LOGGER.debug("Lumberjack skipped claimed tree npc={} tree={} holder={} holderTree={}", npc.npcUuid, position, claim.holderNpcUuid, claim.holderAnchor)
                        }
                    }
                }
            }
        }
        store.markChanged()
        return NpcActionResult.running("scanning the bounded 50x50 work area for wood (${job.scanCursor}/${WORK_OFFSETS.size} columns)")
    }

    /** Renews a transient lease for a durable tree plan, yielding safely if another NPC won it. */
    private fun maintainTreeClaim(
        npc: NpcFacade,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
        snapshot: io.samcnpc.core.api.NpcSnapshot,
    ): NpcActionResult? {
        if (job.abandonTreeAfterPillarCleanup) {
            return null
        }
        val trunkBase = job.trunkBasePosition ?: return null
        return when (val claim = treeWorkClaims.renewOrClaim(npc.npcUuid, job.dimensionId, trunkBase, snapshot.gameTime)) {
            SpatialWorkClaimKernel.Result.Acquired, SpatialWorkClaimKernel.Result.Held -> null
            is SpatialWorkClaimKernel.Result.Contested -> yieldContestedTree(npc, store, job, claim)
        }
    }

    /**
     * A contested tree is never fought over. If this NPC has placed temporary blocks, it first
     * dismantles only its recorded scaffold and then resumes the wider scan; otherwise it yields
     * immediately. This makes claim loss safe across server reloads as well as live encounters.
     */
    private fun yieldContestedTree(
        npc: NpcFacade,
        store: LumberjackDemoStore,
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
            return resumeLogScan(store, job)
        }
        job.abandonTreeAfterPillarCleanup = true
        TemporaryPillarKernel.beginCleanup(session)
        job.phase = LumberjackDemoPhase.PILLAR_CLEANUP
        store.markChanged()
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
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
        snapshot: io.samcnpc.core.api.NpcSnapshot,
    ): NpcActionResult? {
        if (job.phase !in PERSONAL_SPACE_PHASES) {
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
            .filter { observation -> observation.alive && store.jobFor(observation.uuid) != null }
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
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val target = job.targetPosition ?: return resumeLogScan(store, job)
        val observation = world.observeBlock(target)
        if (observation == null || !observation.isLumberjackWorkBlock()) {
            return if (job.blockedLogPosition != null) {
                resumeBlockedLog(store, job)
            } else {
                advanceTrunkPlan(world, store, job)
            }
        }
        val trunkBase = job.trunkBasePosition ?: return resumeLogScan(store, job)
        val clearingFoliage = job.blockedLogPosition != null && world.isLeafOrSupportedSnowObstacle(target)
        val stance = job.miningStance ?: if (clearingFoliage) {
            selectFoliageClearingStance(npc.snapshot().position, target, world)
        } else {
            selectMiningStance(npc.snapshot().position, trunkBase, world, rejectedStances(job))
        }
        if (stance == null) {
            if (clearingFoliage && isWithinDirectFoliageBreakRange(npc.snapshot().position, target)) {
                npc.stopControl()
                job.phase = LumberjackDemoPhase.BREAK_LOG
                store.markChanged()
                return NpcActionResult.running("a leaf is inside the NPC collision corridor; clearing it from the current legal reach")
            }
            return if (clearingFoliage) {
                continueAfterUnreachableWorkBlock(world, store, job)
            } else {
                resumeLogScan(store, job)
            }
        }
        if (job.miningStance == null) {
            job.miningStance = stance
            store.markChanged()
        }
        when (val progress = moveToMiningStance(npc, target, stance)) {
            MoveTowardProgress.ARRIVED -> Unit
            MoveTowardProgress.MOVING -> return NpcActionResult.running("walking to a clear mining stance beside supplied wood")
            is MoveTowardProgress.FAILED -> return finish(server, npc, store, job, progress.result)
        }
        npc.stopControl()
        if (job.initialTrunkTargetPending) {
            val firstLog = world.initialLumberjackTrunkLog(trunkBase, floor(npc.snapshot().eyePosition.y).toInt())
                ?: return resumeLogScan(store, job)
            job.targetPosition = firstLog
            job.initialTrunkTargetPending = false
            store.markChanged()
            return NpcActionResult.running("arrived beside the trunk; selected its first eye-height log")
        }
        job.phase = LumberjackDemoPhase.BREAK_LOG
        store.markChanged()
        return NpcActionResult.running("arrived at a clear mining stance beside supplied wood")
    }

    private fun breakLog(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val target = job.targetPosition ?: return resumeLogScan(store, job)
        val completion = trackedBlockBreaks.takeCompleted(job.npcUuid)
        if (completion != null) {
            if (completion.status != NpcActionStatus.SUCCEEDED) {
                // Leaves can decay between Core accepting an explicit break and its next
                // validation tick. The terminal action is then mechanically FAILED even though
                // the obstruction is already gone. Treat the observed air cell as resolved and
                // resume the exact deferred log instead of abandoning this whole tree.
                if (world.observeBlock(target)?.isLumberjackWorkBlock() != true) {
                    return continueAfterCompletedWorkBlock(store, job)
                }
                return continueAfterUnreachableWorkBlock(world, store, job)
            }
        }
        val observation = world.observeBlock(target)
        if (observation == null || !observation.isLumberjackWorkBlock()) {
            return continueAfterCompletedWorkBlock(store, job)
        }
        // Core owns the validity of an active player-like break. Behavior keeps the head pointed
        // at the caller-selected block so the visible pose and the actual work target agree.
        val activeBreak = npc.snapshot().blockBreak
        if (activeBreak != null) {
            return if (activeBreak.position == target) {
                val look = lookTowards(npc, blockCenter(target))
                if (look.isFailure()) {
                    return finish(server, npc, store, job, look)
                }
                npc.continueBlockBreak()
            } else {
                NpcActionResult.running("waiting for another explicit Core block-break action to finish")
            }
        }
        val stance = job.miningStance
        val snapshot = npc.snapshot()
        val clearingFoliage = job.blockedLogPosition != null && world.isLeafOrSupportedSnowObstacle(target)
        val atStance = stance != null && (
            isWithinPosition(snapshot.position, stance, MINING_STANCE_ARRIVAL_DISTANCE) ||
                job.isStandingOnPreservedStump(snapshot.position)
            ) || (clearingFoliage && isWithinDirectFoliageBreakRange(snapshot.position, target))
        if (!atStance) {
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
            store.markChanged()
            return NpcActionResult.running("NPC left its clear mining stance; navigating back before mining")
        }
        val look = lookTowards(npc, blockCenter(target))
        if (look.isFailure()) {
            return finish(server, npc, store, job, look)
        }
        val started = npc.startBlockBreak(target)
        trackedBlockBreaks.trackStart(job.npcUuid, started)
        if (started.status == NpcActionStatus.REJECTED && started.code == NpcActionCode.OUT_OF_RANGE) {
            val scaffold = job.pillarSession
            if (scaffold != null && scaffold.placedPositions.isNotEmpty()) {
                if (target.y >= floor(npc.snapshot().position.y).toInt()) {
                    TemporaryPillarKernel.continueToTarget(scaffold, target)
                    job.phase = LumberjackDemoPhase.PILLAR_UP
                    store.markChanged()
                    return NpcActionResult.running("the next trunk log is still high; reusing the existing temporary work scaffold")
                }
                TemporaryPillarKernel.beginCleanup(scaffold)
                job.phase = LumberjackDemoPhase.PILLAR_CLEANUP
                store.markChanged()
                LOGGER.info("Lumberjack begins scaffold descent npc={} from={} placed={}", job.npcUuid, npc.snapshot().position, scaffold.placedPositions)
                return NpcActionResult.running("the remaining supplied log is below the scaffold; descending one recorded temporary block at a time")
            }
            if (job.canClimbOntoPreservedStump(world) && !isStandingOnPreservedStump(npc.snapshot().position, job.trunkBasePosition ?: target)) {
                job.phase = LumberjackDemoPhase.CLIMB_TRUNK
                job.climbJumpAttempts = 0
                job.climbForwardTicks = 0
                job.scaffoldMaterialRecovery = false
                store.markChanged()
                return NpcActionResult.running("the next supplied log is above ground reach; stepping onto the preserved stump")
            }
            val lowerStep = world.lowestRemainingLumberjackStep(job.trunkBasePosition, target)
            if (lowerStep != null) {
                job.blockedLogPosition = target
                job.targetPosition = lowerStep
                job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
                store.markChanged()
                return NpcActionResult.running("freeing the lowest remaining trunk block before stepping onto the supplied upper log")
            }
            val trunkBase = job.trunkBasePosition ?: target
            val alternate = selectMiningStance(npc.snapshot().position, trunkBase, world, rejectedStances(job, stance))
            if (alternate != null && job.failedWorkAttempts == 0) {
                job.miningStance = alternate
                job.failedWorkAttempts = 1
                job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
                store.markChanged()
                return NpcActionResult.running("Core reports the supplied wood outside reach; trying another clear side")
            }
            return beginPillarOrContinue(npc, world, store, job)
        }
        if (started.status == NpcActionStatus.REJECTED && started.code == NpcActionCode.WORLD_REJECTED) {
            // Clear only the first exact ray obstruction: a leaf, or a snow layer sitting on a
            // leaf. A snowy canopy therefore resolves naturally as snow -> leaf -> log, never
            // as an arbitrary canopy clear.
            val obstruction = accessObstacleBlockingViewOf(npc, target, world)
            if (obstruction != null && deferAccessObstacle(store, job, obstruction)) {
                return NpcActionResult.running("a leaf or its snow layer blocks the supplied log; clearing that exact obstruction")
            }
            val trunkBase = job.trunkBasePosition ?: target
            val alternate = selectMiningStance(npc.snapshot().position, trunkBase, world, rejectedStances(job, stance))
            if (alternate != null) {
                job.miningStance = alternate
                job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
                store.markChanged()
                return NpcActionResult.running("Core reports the log is not visible; trying another clear mining stance")
            }
            return continueAfterUnreachableWorkBlock(world, store, job)
        }
        return finishIfMechanicalFailure(server, npc, store, job, started)
    }

    private fun climbTrunk(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val trunkBase = job.trunkBasePosition ?: return resumeLogScan(store, job)
        if (!job.canClimbOntoPreservedStump(world)) {
            return beginPillarOrContinue(npc, world, store, job)
        }
        val stumpTop = NpcBlockPosition(trunkBase.x, trunkBase.y + 1, trunkBase.z)
        val snapshot = npc.snapshot()
        if (isStandingOnPreservedStump(snapshot.position, trunkBase)) {
            npc.stopControl()
            job.miningStance = stumpTop
            job.climbJumpAttempts = 0
            job.climbForwardTicks = 0
            job.scaffoldMaterialRecovery = false
            job.phase = LumberjackDemoPhase.BREAK_LOG
            store.markChanged()
            return NpcActionResult.running("standing on the preserved stump; retrying the supplied upper log")
        }
        val launchCell = trunkLaunchCell(trunkBase, job.miningStance)
        if (!world.isClearPlayerStandingCell(launchCell)) {
            return continueAfterUnreachableWorkBlock(world, store, job)
        }
        if (!isWithinPosition(snapshot.position, launchCell, CLIMB_LAUNCH_ARRIVAL_DISTANCE)) {
            job.climbForwardTicks = 0
            // This is the one intentionally direct movement step in the policy. Ground
            // navigation regards the trunk-adjacent launch cell as obstructed and can circle it;
            // a player instead walks straight to the clear edge before jumping onto the log.
            val turn = lookTowards(npc, blockCenter(launchCell))
            if (turn.isFailure()) {
                return finish(server, npc, store, job, turn)
            }
            val forward = npc.applyControl(NpcControlInput(forward = 1.0F, strafe = 0.0F))
            if (forward.isFailure()) {
                return finish(server, npc, store, job, forward)
            }
            return NpcActionResult.running("walking directly to the clear side of the preserved stump")
        }
        val turn = lookTowards(npc, blockCenter(stumpTop))
        if (turn.isFailure()) {
            return finish(server, npc, store, job, turn)
        }
        val forward = npc.applyControl(NpcControlInput(forward = 1.0F, strafe = 0.0F))
        if (forward.isFailure()) {
            return finish(server, npc, store, job, forward)
        }
        if (job.climbForwardTicks > 0) {
            // Keep the same body direction briefly after launch. A Mob's collision hull can
            // otherwise land on the near lip of the log, decide it is grounded, and immediately
            // rotate into another jump without ever committing its momentum onto the step.
            job.climbForwardTicks -= 1
            return NpcActionResult.running("committing forward through the single player-like stump step")
        }
        if (!snapshot.onGround) {
            return NpcActionResult.running("carrying the player-like stump jump forward until normal physics resolves")
        }
        if (job.climbJumpAttempts >= MAX_CLIMB_JUMP_ATTEMPTS) {
            // A missed step is an elevation problem, not permission to bunny-hop at a tree
            // corner. The Behavior-owned pillar executor will use only real inventory material
            // and normal Core placement if the target still needs height.
            return beginPillarOrContinue(npc, world, store, job)
        }
        val jump = npc.jump()
        if (jump.isFailure()) {
            return finish(server, npc, store, job, jump)
        }
        job.climbJumpAttempts += 1
        job.climbForwardTicks = CLIMB_POST_JUMP_FORWARD_TICKS
        store.markChanged()
        return NpcActionResult.running("moving forward and making one committed jump onto the preserved stump")
    }

    /**
     * A navigation request may be physically impossible even though its air-cell observation is
     * valid (for example, leaves, a snow ledge, or a trunk corner block the Mob hull). A vertical
     * hop is not route progress. This Behavior-only watchdog measures only horizontal reduction
     * to its supplied navigation goal and asks the policy to choose another side after a bounded,
     * distance-derived period without meaningful progress.
     */
    private fun hasStalledMovement(job: LumberjackDemoJob, position: NpcPosition): Boolean {
        // A completed natural stump climb deliberately ends in the CLIMB_TRUNK phase for one
        // tick so that the executor can hand control back to BREAK_LOG. Treating that stable
        // work position as a navigation stall prevented the handoff and made high trunks loop.
        if (job.phase == LumberjackDemoPhase.CLIMB_TRUNK && job.isStandingOnPreservedStump(position)) {
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
        val distanceSquared = horizontalDistanceSquared(position, goal)
        return routeProgress.hasStalled(
            job.npcUuid,
            job.phase,
            position,
            goal,
            semanticPathStallTimeout(distanceSquared),
        )
    }

    private fun movementGoal(job: LumberjackDemoJob): NpcPosition? = when (job.phase) {
        LumberjackDemoPhase.TRAVEL_TO_CHEST,
        LumberjackDemoPhase.RETURN_TO_CHEST,
        -> blockNavigationPosition(job.chestPosition)

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
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult? {
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
        if (!deferAccessObstacle(store, job, obstruction)) {
            return null
        }
        routeProgress.clear(job.npcUuid)
        return NpcActionResult.running("a leaf blocked the route for ${watchdog.noProgressTicks} ticks; clearing that horizontal collision cell")
    }

    private fun recoverFromStalledMovement(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        store: LumberjackDemoStore,
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
                    return continueAfterUnreachableWorkBlock(world, store, job)
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
                if (obstruction != null && deferAccessObstacle(store, job, obstruction)) {
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
                    store.markChanged()
                    NpcActionResult.running("the route made no horizontal progress; trying another clear mining side")
                } else {
                    if (job.phase == LumberjackDemoPhase.CLIMB_TRUNK) {
                        beginPillarOrContinue(npc, world, store, job)
                    } else {
                        continueAfterUnreachableWorkBlock(world, store, job)
                    }
                }
            }
            LumberjackDemoPhase.COLLECT_LOG_DROP -> continueAfterUnreachableWorkBlock(world, store, job)
            LumberjackDemoPhase.TRAVEL_TO_CHEST, LumberjackDemoPhase.RETURN_TO_CHEST -> finish(
                server,
                npc,
                store,
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
    private fun deferAccessObstacle(store: LumberjackDemoStore, job: LumberjackDemoJob, obstacle: NpcBlockPosition): Boolean {
        val suppliedLog = job.blockedLogPosition ?: job.targetPosition ?: return false
        if (obstacle == suppliedLog || !leafInSameBoundedTreeVolume(suppliedLog, obstacle)) {
            return false
        }
        job.blockedLogPosition = suppliedLog
        job.targetPosition = obstacle
        job.miningStance = null
        // A world change may make a previously bad side valid. The leaf itself is the only
        // deliberate geometry change in this branch, so it is safe to reconsider the four
        // bounded stances after it has been removed.
        job.rejectedMiningStances.clear()
        job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
        store.markChanged()
        LOGGER.info("Lumberjack clears foliage npc={} obstacle={} deferredLog={}", job.npcUuid, obstacle, suppliedLog)
        return true
    }

    private fun isActiveDeferredFoliageTarget(job: LumberjackDemoJob, world: NpcWorldView): Boolean {
        if (job.blockedLogPosition == null) {
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
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val target = job.targetPosition ?: return resumeLogScan(store, job)
        // Upper logs fall onto the retained base log. Going after every former air cell would
        // make a ground navigator jump at a block that no longer exists; collect them all after
        // the base is finally removed.
        if (target != job.trunkBasePosition) {
            return advanceTrunkPlan(world, store, job)
        }
        // Do not interrupt the vertical cutting plan for a drop sitting on the retained stump.
        // Once the base log is gone, every local drop has a genuine ground route and can be
        // collected deliberately rather than provoking jump-path retries at a former air cell.
        collectNearbyTaskDrop(npc, world, store, job)?.let { result -> return result }
        val pickupBudget = taskDropPickupBudget(job)
        if (job.pickupTicks >= pickupBudget) {
            // The former stump cell is intentionally air now. A bounded local-drop wait is
            // enough; repeatedly asking vanilla navigation for an unreachable former-block
            // cell must never leave the worker in COLLECT_LOG_DROP forever.
            return continueAfterPickupBudget(npc, world, store, job)
        }
        when (val progress = moveToward(npc, target, PICKUP_ARRIVAL_DISTANCE, MoveTarget.CLEARED_BLOCK)) {
            MoveTowardProgress.ARRIVED -> Unit
            MoveTowardProgress.MOVING -> {
                // A queued path can be mechanically valid yet remain unable to reach an empty
                // former-log cell. Count one bounded task-local wait, not unbounded retries.
                job.pickupTicks += 1
                if (job.pickupTicks >= pickupBudget) {
                    return continueAfterPickupBudget(npc, world, store, job)
                }
                store.markChanged()
                return NpcActionResult.running("moving into the player-like pickup envelope for the felled log")
            }
            is MoveTowardProgress.FAILED -> return finish(server, npc, store, job, progress.result)
        }
        npc.stopControl()
        job.pickupTicks += 1
        val pickupSettleTicks = if (job.scaffoldMaterialRecovery) RECOVERED_SCAFFOLD_PICKUP_SETTLE_TICKS else PICKUP_SETTLE_TICKS
        if (job.pickupTicks < pickupSettleTicks) {
            store.markChanged()
            return NpcActionResult.running("allowing Core contact pickup to collect the felled log")
        }
        return continueAfterPickupBudget(npc, world, store, job)
    }

    /** Ends one bounded local drop attempt; it never selects or chases unrelated world items. */
    private fun continueAfterPickupBudget(
        npc: NpcFacade,
        world: NpcWorldView,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        npc.stopControl()
        return if (job.scaffoldMaterialRecovery) {
            resumeWithRecoveredScaffoldMaterial(npc, world, store, job)
        } else {
            advanceTrunkPlan(world, store, job)
        }
    }

    /**
     * Core performs only contact pickup. This policy decides to walk to bounded drops caused by
     * its own local tree work; it never searches the world for arbitrary player loot.
     */
    private fun collectNearbyTaskDrop(
        npc: NpcFacade,
        world: NpcWorldView,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult? {
        val trunkBase = job.trunkBasePosition ?: return null
        // This is one aggregate budget for one felled block, not a timeout per item. Each
        // successful pickup used to reset it, allowing a stream of real drops to postpone the
        // next trunk step or chest return forever.
        val pickupBudget = taskDropPickupBudget(job)
        if (job.pickupTicks >= pickupBudget) {
            return null
        }
        val snapshot = npc.snapshot()
        val drop = world.queryEntities(
            NpcEntityQuery(
                center = snapshot.position,
                radius = TASK_DROP_SEARCH_RADIUS,
                limit = MAX_TASK_DROP_OBSERVATIONS,
                typeIds = setOf("minecraft:item"),
            ),
        )
            .asSequence()
            .filter { entity -> entity.alive && entity.isLumberjackTaskDrop() }
            .filter { entity -> isWithinTreeDropArea(entity.position, trunkBase) }
            // Upper logs land on the intentionally retained trunk. They are not a ground route
            // and a normal player would finish the support first rather than path-jump at the
            // floating drop. Once the base is removed, the same items fall into this envelope.
            .filter { entity -> entity.position.y <= snapshot.position.y + ACTIVE_PICKUP_MAX_HEIGHT_ABOVE_FEET }
            .minWithOrNull(
                compareBy<io.samcnpc.core.api.NpcEntityObservation> { entity -> distanceSquared(snapshot.position, entity.position) }
                    .thenBy { entity -> entity.uuid.toString() },
            )
            ?: return null
        if (distanceSquared(snapshot.position, drop.position) > ACTIVE_PICKUP_REACH_SQR) {
            val navigation = npc.navigateTo(drop.position, NAVIGATION_SPEED_MULTIPLIER)
            if (navigation.isFailure()) {
                recordTaskDropPickupTick(store, job)
                return NpcActionResult.running("a nearby task drop cannot currently be reached; leaving it for normal contact pickup")
            }
            val look = lookTowards(npc, drop.position)
            if (look.isFailure()) {
                recordTaskDropPickupTick(store, job)
                return NpcActionResult.running("walking toward a nearby task drop")
            }
            recordTaskDropPickupTick(store, job)
            return NpcActionResult.running("walking to a nearby leaf, snow, or wood drop from this tree")
        }
        npc.stopControl()
        val pickup = npc.pickupItem(drop.uuid)
        if (pickup.status == NpcActionStatus.SUCCEEDED) {
            recordTaskDropPickupTick(store, job)
            return NpcActionResult.running("collected a nearby drop from the selected tree")
        }
        job.pickupTicks += 1
        if (job.pickupTicks < pickupBudget) {
            store.markChanged()
            return NpcActionResult.running("waiting for a nearby task drop to become collectible")
        }
        return null
    }

    private fun recordTaskDropPickupTick(store: LumberjackDemoStore, job: LumberjackDemoJob) {
        job.pickupTicks += 1
        store.markChanged()
    }

    private fun taskDropPickupBudget(job: LumberjackDemoJob): Int =
        if (job.scaffoldMaterialRecovery) RECOVERED_SCAFFOLD_PICKUP_SETTLE_TICKS else MAX_TASK_DROP_PICKUP_WAIT_TICKS

    private fun returnToChest(server: MinecraftServer, npc: NpcFacade, store: LumberjackDemoStore, job: LumberjackDemoJob): NpcActionResult {
        when (val progress = moveToward(npc, job.chestPosition, CHEST_ARRIVAL_DISTANCE)) {
            MoveTowardProgress.ARRIVED -> Unit
            MoveTowardProgress.MOVING -> return NpcActionResult.running("returning wood to the selected chest")
            is MoveTowardProgress.FAILED -> return finish(server, npc, store, job, progress.result)
        }
        npc.stopControl()
        job.phase = LumberjackDemoPhase.DEPOSIT_WOOD
        store.markChanged()
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

    private fun beginCapacityWoodReturn(store: LumberjackDemoStore, job: LumberjackDemoJob): NpcActionResult {
        job.resumeWorkAfterDeposit = true
        job.phase = LumberjackDemoPhase.RETURN_TO_CHEST
        store.markChanged()
        return NpcActionResult.running("inventory reached $CAPACITY_RETURN_PERCENT% occupied slots; returning gathered wood before continuing the bounded work")
    }

    private fun depositWood(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val chest = world.observeBlockContainer(job.chestPosition)
            ?: return finish(server, npc, store, job, NpcActionResult.failed("selected chest is no longer a readable nearby container", NpcActionCode.NOT_FOUND))
        val deposit = InventoryBaselineKernel.excessStacks(npc, job.initialWoodCounts, ::isWoodItem).firstOrNull()
            ?: return if (job.resumeWorkAfterDeposit) {
                resumeAfterCapacityWoodDeposit(world, store, job)
            } else {
                finish(server, npc, store, job, NpcActionResult.succeeded("lumberjack demo complete; gathered wood was returned to the chest"))
            }
        val destination = chest.destinationFor(deposit.itemId)
            ?: return finish(
                server,
                npc,
                store,
                job,
                NpcActionResult.failed("selected chest has no room for gathered ${deposit.itemId}; wood remains in NPC inventory", NpcActionCode.MISSING_RESOURCE),
            )
        return finishIfMechanicalFailure(
            server,
            npc,
            store,
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
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        job.resumeWorkAfterDeposit = false
        val target = job.targetPosition
        if (target != null && world.observeBlock(target)?.isLumberjackWorkBlock() == true) {
            job.miningStance = null
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
            store.markChanged()
            return NpcActionResult.running("capacity chest run is complete; returning to the saved tree task")
        }
        if (job.trunkBasePosition != null) {
            return advanceTrunkPlan(world, store, job)
        }
        job.phase = LumberjackDemoPhase.SEARCH_WOOD
        store.markChanged()
        return NpcActionResult.running("capacity chest run is complete; resuming the bounded wood scan")
    }

    private fun beginLogCollection(store: LumberjackDemoStore, job: LumberjackDemoJob): NpcActionResult {
        trackedBlockBreaks.clear(job.npcUuid)
        job.failedWorkAttempts = 0
        job.phase = LumberjackDemoPhase.COLLECT_LOG_DROP
        job.pickupTicks = 0
        store.markChanged()
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
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult? {
        val target = job.targetPosition ?: return null
        val trunkBase = job.trunkBasePosition ?: return null
        if (target == trunkBase) {
            return null
        }
        if (world.observeBlock(trunkBase)?.isLumberjackWoodLog() != true) {
            // This is the second and later wood-only elevation of the same tree. The retained
            // stump has already been felled, so do not reset the whole plan merely because the
            // next short pillar needs one more recovered log. Reuse the same bounded pickup
            // phase; it observes only task drops inside this trunk's local volume.
            if (!job.scaffoldMaterialRecovery) {
                return null
            }
            job.blockedLogPosition = target
            job.targetPosition = trunkBase
            job.miningStance = null
            job.pickupTicks = 0
            job.phase = LumberjackDemoPhase.COLLECT_LOG_DROP
            store.markChanged()
            return NpcActionResult.running("the retained stump is already gone; gathering nearby recovered scaffold wood")
        }
        job.blockedLogPosition = target
        job.targetPosition = trunkBase
        job.miningStance = null
        job.scaffoldMaterialRecovery = true
        job.scaffoldMaterialRecoveryPending = false
        job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
        store.markChanged()
        return NpcActionResult.running("no cheap scaffold block is carried; felling the retained stump to recover already-cut wood")
    }

    private fun resumeWithRecoveredScaffoldMaterial(
        npc: NpcFacade,
        world: NpcWorldView,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val deferredLog = job.blockedLogPosition ?: return resumeLogScan(store, job)
        job.targetPosition = deferredLog
        job.blockedLogPosition = null
        job.miningStance = null
        store.markChanged()
        return beginPillarOrContinue(npc, world, store, job)
    }

    private fun continueAfterCompletedWorkBlock(store: LumberjackDemoStore, job: LumberjackDemoJob): NpcActionResult = when {
        job.scaffoldMaterialRecovery && job.targetPosition == job.trunkBasePosition -> beginLogCollection(store, job)
        job.blockedLogPosition != null -> resumeBlockedLog(store, job)
        else -> beginLogCollection(store, job)
    }

    /** Continue with the exact upper log deferred by a leaf or a required stepping block. */
    private fun resumeBlockedLog(store: LumberjackDemoStore, job: LumberjackDemoJob): NpcActionResult {
        val blockedLog = job.blockedLogPosition ?: return resumeLogScan(store, job)
        job.targetPosition = blockedLog
        job.blockedLogPosition = null
        job.miningStance = null
        job.scaffoldMaterialRecovery = false
        job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
        store.markChanged()
        LOGGER.info("Lumberjack foliage path cleared npc={} resumingLog={}", job.npcUuid, blockedLog)
        return NpcActionResult.running("temporary trunk obstruction cleared; resuming the same supplied upper log")
    }

    /** The demo climbs and clears one claimed 1x1 trunk; leaves are only access obstructions. */
    private fun advanceTrunkPlan(world: NpcWorldView, store: LumberjackDemoStore, job: LumberjackDemoJob): NpcActionResult {
        val trunkBase = job.trunkBasePosition ?: return resumeLogScan(store, job)
        val completed = job.targetPosition ?: return resumeLogScan(store, job)
        val above = NpcBlockPosition(trunkBase.x, completed.y + 1, trunkBase.z)
        if (world.observeBlock(above)?.isLumberjackWoodLog() == true) {
            job.targetPosition = above
            job.pickupTicks = 0
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
            store.markChanged()
            return NpcActionResult.running("continuing upward through the supplied trunk")
        }
        // The base remains as an intentional, player-like one-block step until every higher log
        // is gone. Foliage does not become a separate cleanup task for this demo.
        val retainedUpperLog = highestRemainingLumberjackTrunkLog(world, trunkBase)
            ?.takeIf { candidate -> candidate != trunkBase }
        if (retainedUpperLog != null) {
            job.targetPosition = retainedUpperLog
            job.pickupTicks = 0
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
            store.markChanged()
            return NpcActionResult.running("finishing the remaining reachable trunk")
        }
        return finishRetainedTrunk(world, store, job, trunkBase)
    }

    private fun finishRetainedTrunk(
        world: NpcWorldView,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
        trunkBase: NpcBlockPosition,
    ): NpcActionResult {
        val retainedLowerLog = highestRemainingLumberjackTrunkLog(world, trunkBase)
        if (retainedLowerLog != null) {
            job.targetPosition = retainedLowerLog
            job.pickupTicks = 0
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
            store.markChanged()
            return NpcActionResult.running("upper trunk complete; finishing the retained lower trunk")
        }
        return resumeLogScan(store, job)
    }

    /**
     * An unreachable supplied block must not poison the whole bounded scan. A ray-confirmed leaf
     * is only ever an immediate, temporary obstruction; otherwise this retries the same target
     * from a fresh stance before giving up this tree.
     */
    private fun continueAfterUnreachableWorkBlock(
        world: NpcWorldView,
        store: LumberjackDemoStore,
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
            store.markChanged()
            return NpcActionResult.running(
                "Core rejected the supplied work block; retrying it from a fresh stance (${job.failedWorkAttempts}/$MAX_FAILED_WORK_ATTEMPTS)",
            )
        }
        return resumeLogScan(store, job)
    }

    /**
     * This is deliberately reached only after the supplied log has failed normal reach, an
     * existing stump, a lower trunk step and a clear alternate side. Core receives no request
     * other than the ordinary player-like primitive calls issued by [TemporaryPillarKernel].
     */
    private fun beginPillarOrContinue(
        npc: NpcFacade,
        world: NpcWorldView,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val target = job.targetPosition ?: return resumeLogScan(store, job)
        val physical = npc.snapshot()
        if (!physical.onGround && !physical.inWater && !physical.inLava && !physical.climbing && !physical.riding) {
            // A normal step/jump can leave the body airborne for a few ticks. Do not consume a
            // bounded retry or start another task while its survival physics are unresolved.
            job.phase = LumberjackDemoPhase.PILLAR_UP
            store.markChanged()
            return NpcActionResult.running("the supplied log still needs elevation; waiting to stabilize before evaluating the pillar base")
        }
        when (val begun = TemporaryPillarKernel.begin(npc, world, target)) {
            is TemporaryPillarKernel.PillarBeginResult.Started -> {
                job.pillarSession = begun.session
                job.phase = LumberjackDemoPhase.PILLAR_UP
                store.markChanged()
                LOGGER.info("Lumberjack pillar started npc={} target={} estimate={} material={} slot={}", job.npcUuid, target, begun.session.estimatedLevels, begun.session.materialItemId, begun.session.materialOriginalSlot)
                return NpcActionResult.running("the supplied log remains unreachable after natural geometry; starting one-level player-like elevation")
            }
            is TemporaryPillarKernel.PillarBeginResult.BlockedByLeaf -> {
                if (deferAccessObstacle(store, job, begun.position)) {
                    return NpcActionResult.running("one leaf or its snow layer physically blocks the jump headroom; clearing only that obstruction")
                }
                return continueAfterUnreachableWorkBlock(world, store, job)
            }
            is TemporaryPillarKernel.PillarBeginResult.Failed -> {
                LOGGER.info("Lumberjack pillar did not start npc={} target={} code={}", job.npcUuid, target, begun.code)
                if (begun.code in SCAFFOLD_MATERIAL_FAILURES) {
                    LOGGER.info(
                        "Lumberjack scaffold material inventory npc={} target={} stacks={}",
                        job.npcUuid,
                        target,
                        npc.inventoryContents()
                            .filter { entry -> !entry.stack.isEmpty && TemporaryPillarKernel.isPermittedMaterial(entry.knowledge) }
                            .joinToString(prefix = "[", postfix = "]") { entry -> "${entry.stack.itemId}x${entry.stack.count}@${entry.slot}" },
                    )
                    beginScaffoldMaterialRecovery(world, store, job)?.let { recovery -> return recovery }
                }
                return continueAfterUnreachableWorkBlock(world, store, job)
            }
        }
    }

    private fun advancePillar(
        server: MinecraftServer,
        npc: NpcFacade,
        world: NpcWorldView,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val session = job.pillarSession
        if (session == null) {
            val physical = npc.snapshot()
            return if (!physical.onGround && !physical.inWater && !physical.inLava && !physical.climbing && !physical.riding) {
                NpcActionResult.running("waiting for stable footing before beginning temporary elevation")
            } else {
                beginPillarOrContinue(npc, world, store, job)
            }
        }
        return when (val progress = TemporaryPillarKernel.tick(npc, world, session)) {
            is TemporaryPillarKernel.PillarProgress.Running -> {
                store.markChanged()
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
                store.markChanged()
                NpcActionResult.running("temporary elevation made the supplied log reachable; resuming normal chopping")
            }
            TemporaryPillarKernel.PillarProgress.TargetGone -> {
                TemporaryPillarKernel.beginCleanup(session)
                job.phase = LumberjackDemoPhase.PILLAR_CLEANUP
                store.markChanged()
                NpcActionResult.running("the elevated target disappeared; descending the recorded temporary scaffold")
            }
            is TemporaryPillarKernel.PillarProgress.BlockedByLeaf -> {
                // The leaf is only a temporary headroom obstruction. Keep ownership of every
                // already placed scaffold block so the deferred log can reuse it and later
                // cleanup cannot accidentally abandon player-built world state.
                if (deferAccessObstacle(store, job, progress.position)) {
                    return NpcActionResult.running("one leaf or its snow layer blocks the jump headroom; clearing it before resuming elevation")
                }
                continueAfterUnreachableWorkBlock(world, store, job)
            }
            is TemporaryPillarKernel.PillarProgress.Failed -> {
                LOGGER.info("Lumberjack pillar stopped npc={} target={} code={} detail={}", job.npcUuid, job.targetPosition, progress.code, progress.detail)
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
                    job.abandonTreeAfterPillarCleanup = true
                }
                if (session.placedPositions.isNotEmpty()) {
                    // A failed next level must never erase ownership of the honest levels below
                    // it. Cleanup itself waits for normal landing if this failure occurred while
                    // airborne, then removes only the positions recorded by this session.
                    TemporaryPillarKernel.beginCleanup(session)
                    job.phase = LumberjackDemoPhase.PILLAR_CLEANUP
                    store.markChanged()
                    return NpcActionResult.running("pillar stopped after placing temporary blocks; resolving the recorded scaffold safely")
                }
                job.pillarSession = null
                resumeLogScan(store, job)
            }
            TemporaryPillarKernel.PillarProgress.CleanupComplete, TemporaryPillarKernel.PillarProgress.CleanupIncomplete ->
                finish(server, npc, store, job, NpcActionResult.failed("unexpected cleanup result in active pillar phase", NpcActionCode.WORLD_REJECTED))
        }
    }

    private fun cleanPillar(
        store: LumberjackDemoStore,
        npc: NpcFacade,
        world: NpcWorldView,
        job: LumberjackDemoJob,
    ): NpcActionResult {
        val session = job.pillarSession ?: run {
            job.phase = LumberjackDemoPhase.RETURN_TO_CHEST
            store.markChanged()
            return NpcActionResult.running("no temporary scaffold remains; returning to the chest")
        }
        return when (val progress = TemporaryPillarKernel.tickCleanup(npc, world, session)) {
            is TemporaryPillarKernel.PillarProgress.Running -> {
                store.markChanged()
                NpcActionResult.running(progress.detail)
            }
            TemporaryPillarKernel.PillarProgress.CleanupComplete -> {
                job.pillarSession = null
                resumeAfterPillarCleanup(world, store, job)
                store.markChanged()
                LOGGER.info("Lumberjack scaffold cleanup handoff npc={} position={} target={}", job.npcUuid, npc.snapshot().position, job.targetPosition)
                NpcActionResult.running("temporary scaffold was removed with normal block breaks; resuming the supplied task")
            }
            TemporaryPillarKernel.PillarProgress.CleanupIncomplete -> {
                // Honest cleanup never reaches into unrelated geometry. The recorded positions
                // remain in the log, while the deployment-only task may still finish its wood run.
                LOGGER.warn("Lumberjack left temporary scaffold npc={} positions={} reason={}", job.npcUuid, session.placedPositions, session.lastResult)
                job.pillarSession = null
                resumeAfterPillarCleanup(world, store, job)
                store.markChanged()
                NpcActionResult.running("temporary scaffold cleanup is incomplete; no unrelated blocks were touched")
            }
            else -> {
                LOGGER.warn("Lumberjack scaffold cleanup transitioned unexpectedly npc={} progress={} state={} positions={}", job.npcUuid, progress, session.state, session.placedPositions)
                job.pillarSession = null
                resumeAfterPillarCleanup(world, store, job)
                store.markChanged()
                NpcActionResult.running("temporary scaffold no longer has a safe cleanup path")
            }
        }
    }

    private fun resumeAfterPillarCleanup(world: NpcWorldView, store: LumberjackDemoStore, job: LumberjackDemoJob) {
        if (job.abandonTreeAfterPillarCleanup) {
            LOGGER.info("Lumberjack yielded tree after pillar cleanup npc={} target={} failedAttempts={}", job.npcUuid, job.targetPosition, job.failedWorkAttempts)
            resumeLogScan(store, job)
            return
        }
        if (job.scaffoldMaterialRecoveryPending) {
            job.scaffoldMaterialRecoveryPending = false
            beginScaffoldMaterialRecovery(world, store, job)?.let {
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
                job.blockedLogPosition = target
                job.targetPosition = trunkBase
                job.miningStance = null
                job.phase = LumberjackDemoPhase.COLLECT_LOG_DROP
                job.pickupTicks = 0
                store.markChanged()
                LOGGER.info("Lumberjack collects recovered scaffold wood npc={} deferredTarget={}", job.npcUuid, target)
                return
            }
            job.miningStance = null
            job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
            LOGGER.info("Lumberjack scaffold cleanup complete npc={} target={} is still work; returning to its normal mining stance", job.npcUuid, target)
        } else {
            job.phase = LumberjackDemoPhase.RETURN_TO_CHEST
            LOGGER.info("Lumberjack scaffold cleanup complete npc={} target={} no longer needs work", job.npcUuid, target)
        }
    }

    private fun resumeLogScan(store: LumberjackDemoStore, job: LumberjackDemoJob): NpcActionResult {
        clearTreePlan(job)
        job.phase = LumberjackDemoPhase.SEARCH_WOOD
        store.markChanged()
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
        job.scaffoldMaterialRecoveryPending = false
        job.rejectedMiningStances.clear()
        job.initialTrunkTargetPending = false
        job.climbJumpAttempts = 0
        job.climbForwardTicks = 0
        job.failedWorkAttempts = 0
        job.abandonTreeAfterPillarCleanup = false
        job.pickupTicks = 0
    }

    private fun NpcActionResult.isFailure(): Boolean = status == NpcActionStatus.REJECTED ||
        status == NpcActionStatus.FAILED || status == NpcActionStatus.UNSUPPORTED

    private fun finishIfMechanicalFailure(
        server: MinecraftServer,
        npc: NpcFacade,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
        result: NpcActionResult,
    ): NpcActionResult = when (result.status) {
        NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED -> finish(server, npc, store, job, result)
        else -> result
    }

    private fun finish(
        server: MinecraftServer,
        npc: NpcFacade,
        store: LumberjackDemoStore,
        job: LumberjackDemoJob,
        result: NpcActionResult,
    ): NpcActionResult {
        npc.stopControl()
        routeProgress.clear(job.npcUuid)
        upwardStareRecovery.clear(job.npcUuid)
        trackedBlockBreaks.clear(job.npcUuid)
        treeWorkClaims.release(job.npcUuid)
        neighborRepulsion.clear(job.npcUuid)
        store.remove(job.npcUuid)
        restorePreviousPacks(server, job.npcUuid, job)
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

    private fun io.samcnpc.core.api.NpcEntityObservation.isLumberjackTaskDrop(): Boolean {
        val itemId = itemStack?.itemId ?: return false
        val path = itemId.substringAfter(':', itemId)
        return isWoodItem(itemId) || path.endsWith("_sapling") || path.endsWith("_leaves") ||
            itemId == "minecraft:stick" || itemId == "minecraft:apple" || itemId == "minecraft:snowball"
    }

    private fun isWithinTreeDropArea(position: NpcPosition, trunkBase: NpcBlockPosition): Boolean =
        kotlin.math.abs(position.x - trunkBase.x - 0.5) <= TASK_DROP_TREE_HORIZONTAL_RADIUS &&
            kotlin.math.abs(position.z - trunkBase.z - 0.5) <= TASK_DROP_TREE_HORIZONTAL_RADIUS &&
            kotlin.math.abs(position.y - trunkBase.y) <= TASK_DROP_TREE_VERTICAL_RADIUS

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
    private const val CHEST_ARRIVAL_DISTANCE = 3.5
    private const val CLIMB_LAUNCH_ARRIVAL_DISTANCE = 0.55
    // A failed natural stump step is an escalation signal, not permission to bunny-hop at the
    // same collision. The post-launch forward commitment gives this one player-like attempt
    // enough momentum before the policy selects a scaffold.
    private const val MAX_CLIMB_JUMP_ATTEMPTS = 1
    private const val CLIMB_POST_JUMP_FORWARD_TICKS = 6
    private const val PICKUP_ARRIVAL_DISTANCE = 0.95
    private const val PICKUP_SETTLE_TICKS = 5
    // A wood-only elevation fallback must wait for every upper-log drop to land after the retained
    // stump is felled. Starting the next pillar from the first pickup can leave it one block short
    // with no safe base remaining for another recovery.
    private const val RECOVERED_SCAFFOLD_PICKUP_SETTLE_TICKS = 30
    private const val INVENTORY_SIZE = 36
    private const val CAPACITY_RETURN_PERCENT = 70
    private const val PERCENT_SCALE = 100
    private const val TASK_DROP_SEARCH_RADIUS = 8.0
    private const val TASK_DROP_TREE_HORIZONTAL_RADIUS = 7.0
    private const val TASK_DROP_TREE_VERTICAL_RADIUS = 8.0
    private const val MAX_TASK_DROP_OBSERVATIONS = 12
    private const val ACTIVE_PICKUP_REACH_SQR = 2.0 * 2.0
    private const val ACTIVE_PICKUP_MAX_HEIGHT_ABOVE_FEET = 0.9
    private const val MAX_TASK_DROP_PICKUP_WAIT_TICKS = 20
    private const val PERSONAL_SPACE_QUERY_RADIUS = 3.0
    private const val MAX_PERSONAL_SPACE_OBSERVATIONS = 12
    private const val PERSONAL_SPACE_NAVIGATION_SPEED = 0.75F
    private const val LOG_SCAN_COLUMNS_PER_TICK = 128
    private const val MAX_FAILED_WORK_ATTEMPTS = 3
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
