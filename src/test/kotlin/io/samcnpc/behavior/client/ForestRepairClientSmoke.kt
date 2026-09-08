package io.samcnpc.behavior.client

import com.mojang.logging.LogUtils
import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.lumberjack.blockCenter
import io.samcnpc.behavior.lumberjack.blockNavigationPosition
import io.samcnpc.behavior.lumberjack.foliageObstacleOnNavigationAxes
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.behavior.lumberjack.model.LumberjackChestAccessStage
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcRaycastRequest
import io.samcnpc.core.api.NpcVector
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.GameType
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.sqrt

/** Replays the paused three-NPC scene in a fresh copy, never the player's live save. */
@Mod.EventBusSubscriber(modid = SamcnpcBehavior.MOD_ID, value = [Dist.CLIENT])
object ForestRepairClientSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.forestRepairSmoke")
    private val sam3Only = java.lang.Boolean.getBoolean("samcnpc.forestSam3Only")
    private val worldId = System.getProperty("samcnpc.forestSmokeWorld", "")
    private val logger = LogUtils.getLogger()
    private val report = Path.of("forest-repair-result.txt")
    private val sam = UUID.fromString("3933426c-ecb8-40ac-a764-d5d1fd4f34f8")
    private val sam2 = UUID.fromString("2d0e4f62-f7bf-444e-aff1-80a34d2f5d30")
    private val sam3 = UUID.fromString("c626a363-8d49-4916-b987-852fcfef61ff")
    private val ids = if (sam3Only) listOf(sam3) else listOf(sam, sam2, sam3)
    private data class Sample(val entityIds: List<Int>, val finished: Boolean, val evidence: String)
    @Volatile private var sample: Sample? = null

    // Server state.
    private var started = false
    private var ticks = 0
    private var foundAxe = false
    private var returned = false
    private var sam3InitialWood = 0
    private var sam3CollectedRouteWood = false
    private val pillarTasks = mutableSetOf<UUID>()
    private val supports = mutableSetOf<NpcBlockPosition>()

    // Client state; immutable samples are the only shared game information.
    private var worldRequested = false
    private var clientTicks = 0
    private var glowingTicks = 0
    private var clearedTicks = 0
    private var glowingFrames = 0
    private var capturedGlow = false
    private var pendingScreenshot: String? = null
    private var done = false

    @SubscribeEvent
    fun clientTick(event: TickEvent.ClientTickEvent) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return
        val minecraft = Minecraft.getInstance()
        check(++clientTicks < 4000) { "Forest repair client timed out" }
        if (minecraft.screen is AccessibilityOnboardingScreen && minecraft.overlay == null) minecraft.screen?.onClose()
        if (!worldRequested && minecraft.screen is TitleScreen && minecraft.overlay == null) {
            check(worldId.startsWith("forest-repair-"))
            worldRequested = true
            minecraft.options.pauseOnLostFocus = false
            minecraft.options.renderDistance().set(6)
            minecraft.options.simulationDistance().set(6)
            minecraft.createWorldOpenFlows().loadLevel(minecraft.screen, worldId)
        }
        val current = sample ?: return
        val level = minecraft.level ?: return
        val bodies = current.entityIds.mapNotNull { level.getEntity(it) as? LivingEntity }
        if (bodies.size == ids.size && bodies.all { it.isCurrentlyGlowing && minecraft.shouldEntityAppearGlowing(it) }) {
            glowingTicks++
            if (glowingTicks >= 20 && !capturedGlow) {
                capturedGlow = true
                pendingScreenshot = "three-npcs-glowing.png"
            }
        }
        if (!current.finished) return
        if (bodies.size == ids.size && bodies.none { it.isCurrentlyGlowing }) clearedTicks++
        if (clearedTicks == 5) pendingScreenshot = "three-npcs-completed.png"
        if (clearedTicks < 20) return
        check(glowingTicks >= 20 && glowingFrames > 0 && capturedGlow) { "Glowing was not actually synchronized and rendered" }
        done = true
        Files.writeString(report, "${current.evidence}\nglowingTicks=$glowingTicks glowingFrames=$glowingFrames clearedTicks=$clearedTicks\nPASS\n")
        minecraft.stop()
    }

    @SubscribeEvent
    fun serverTick(event: TickEvent.ServerTickEvent) {
        if (!enabled || event.phase != TickEvent.Phase.END || sample?.finished == true) return
        val server = event.server
        if (server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString() != worldId) return
        val player = server.playerList.players.firstOrNull() ?: return
        val level = server.overworld()
        val service = CoreNpcApi.service(server)
        val npcs = ids.mapNotNull { id -> service.find(id)?.let(service::runtime) }
        check(++ticks < 2400) { "Forest replay timed out: " + ids.joinToString { "$it: ${LumberjackService.status(server, it)}" } }
        if (npcs.size != ids.size) return
        if (sam3Only) {
            tickSam3Return(server, player, npcs.single())
            return
        }
        val store = LumberjackDemoStore.forServer(server)
        if (!started) {
            // Sam's old job was already discarded by its failed return. Recreate that exact
            // return continuation at its saved position, including the carried wood to deposit.
            check(store.jobFor(sam) == null && store.jobFor(sam3) == null)
            val previousPacks = BehaviorRuntimeService.assignedPacks(server, sam)
            check(BehaviorRuntimeService.assignPacks(server, sam, listOf(LumberjackService.PACK_ID)).status == NpcActionStatus.SUCCEEDED)
            store.put(LumberjackDemoJob(
                npcUuid = sam, dimensionId = "minecraft:overworld", chestPosition = NpcBlockPosition(1, 68, 7),
                workCenter = NpcBlockPosition(-32, 70, 6), previousPackIds = previousPacks,
                phase = LumberjackDemoPhase.RETURN_TO_CHEST, scanCursor = 2500, pickupTicks = 0,
                resumeWorkAfterDeposit = false, targetPosition = null, trunkBasePosition = null,
                blockedLogPosition = null, miningStance = null, rejectedMiningStances = mutableListOf(),
                initialTrunkTargetPending = false, climbJumpAttempts = 0, climbForwardTicks = 0,
                scaffoldMaterialRecovery = false, scaffoldMaterialRecoveryPending = false,
                failedWorkAttempts = 0, abandonTreeAfterPillarCleanup = false, initialWoodCounts = emptyMap(),
            ))
            val third = npcs.single { it.npcUuid == sam3 }
            check(LumberjackService.start(server, third, third.worldView()).status == NpcActionStatus.SUCCEEDED)
            check(store.jobFor(sam3)?.chestPosition == NpcBlockPosition(1, 68, 7)) { "Sam3 must address the original empty half" }
            // Exhaust only the subsequent forest scan; retain the saved target, pillar, drops,
            // equipment and geometry so completion of the failing work is directly observable.
            checkNotNull(store.jobFor(sam2)).scanCursor = 2500
            checkNotNull(store.jobFor(sam3)).scanCursor = 2500
            store.markChanged()
            for (id in ids) check(server.commands.performPrefixedCommand(player.createCommandSourceStack(), "samcnpc effects $id glowing infinite 0 true") == 1)
            player.setGameMode(GameType.SPECTATOR)
            player.teleportTo(level, 14.0, 77.0, 25.0, 142.4F, 23.4F)
            started = true
        }
        val second = store.jobFor(sam2)
        second?.pillarSession?.let {
            pillarTasks.add(it.taskId)
            supports.addAll(it.placedPositions)
        }
        check(pillarTasks.size <= 3) { "Original pillar loop resumed: ${LumberjackService.status(server, sam2)}" }
        val third = npcs.single { it.npcUuid == sam3 }
        foundAxe = foundAxe || third.inventoryContents().any { it.stack.itemId?.endsWith("_axe") == true }
        val first = npcs.single { it.npcUuid == sam }
        if (store.jobFor(sam) == null) {
            val position = first.snapshot().position
            val dx = position.x - 1.5
            val dy = position.y - 68.5
            val dz = position.z - 7.5
            check(dx * dx + dy * dy + dz * dz <= 4.5 * 4.5) { "Sam stopped before reaching the chest: $position" }
            check(first.inventoryContents().none { it.stack.itemId?.endsWith("_log") == true }) { "Sam did not deposit its carried wood" }
            returned = true
        }
        if (ticks % 40 == 0) logger.info("FOREST REPAIR tick={} axe={} returned={} targetGone={} states={}", ticks, foundAxe, returned,
            level.getBlockState(BlockPos(9, 72, 9)).isAir, ids.map { LumberjackService.status(server, it) })
        val finished = foundAxe && returned && ids.all { store.jobFor(it) == null }
        if (finished) {
            check(level.getBlockState(BlockPos(9, 72, 9)).isAir) { "The original upper log was abandoned" }
            check(supports.isNotEmpty() && supports.all { level.getBlockState(BlockPos(it.x, it.y, it.z)).isAir }) { "Recorded scaffold remained: $supports" }
            for (id in ids) check(server.commands.performPrefixedCommand(player.createCommandSourceStack(), "samcnpc effects $id clear") == 1)
        }
        val entityIds = ids.map { checkNotNull(level.getEntity(it)).id }
        sample = Sample(entityIds, finished, "world=$worldId ticks=$ticks SamReturned=$returned Sam3Axe=$foundAxe pillarTasks=${pillarTasks.size} cleanedSupports=${supports.size}")
    }

    private fun tickSam3Return(server: MinecraftServer, player: ServerPlayer, npc: NpcFacade) {
        val level = server.overworld()
        val store = LumberjackDemoStore.forServer(server)
        if (!started) {
            check(store.jobFor(sam3) == null)
            check(level.getBlockState(BlockPos(20, 68, 17)).`is`(net.minecraft.world.level.block.Blocks.SPRUCE_LOG)) { "Saved scene lost its blocking trunk" }
            sam3InitialWood = spruceWoodCount(npc)
            check(LumberjackService.start(server, npc, npc.worldView()).status == NpcActionStatus.SUCCEEDED)
            val job = checkNotNull(store.jobFor(sam3))
            check(job.chestPosition == NpcBlockPosition(1, 68, 8))
            job.scanCursor = 2500
            store.markChanged()
            val approach = job.chestApproach ?: job.chestPosition
            val candidate = foliageObstacleOnNavigationAxes(npc, blockNavigationPosition(approach), npc.worldView())
            if (candidate != null) {
                val eye = npc.snapshot().eyePosition
                val center = blockCenter(candidate)
                val delta = NpcVector(center.x - eye.x, center.y - eye.y, center.z - eye.z)
                val distance = sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z)
                val hit = npc.worldView().raycast(NpcRaycastRequest(eye, delta, distance + 0.01))
                logger.info("SAM3 ACCESS REPRO feet={} eye={} approach={} axisCandidate={} candidateBlock={} ray={}",
                    npc.snapshot().position, eye, approach, candidate, npc.worldView().observeBlock(candidate), hit)
            }
            logger.info("SAM3 ACCESS nearby={}",
                (65..69).flatMap { y -> (17..19).map { z ->
                    "y=$y z=$z x19..21=" + (19..21).joinToString(",") { x -> npc.worldView().observeBlock(NpcBlockPosition(x, y, z))?.blockId ?: "unknown" }
                } })
            server.commands.performPrefixedCommand(player.createCommandSourceStack(), "samcnpc effects $sam3 clear glowing")
            check(server.commands.performPrefixedCommand(player.createCommandSourceStack(), "samcnpc effects $sam3 glowing infinite 0 true") == 1)
            player.setGameMode(GameType.SPECTATOR)
            player.teleportTo(level, 25.5, 73.0, 22.5, 132.0F, 27.0F)
            started = true
        }
        val job = store.jobFor(sam3)
        if (job?.chestAccessStage == LumberjackChestAccessStage.COLLECT_WOOD && spruceWoodCount(npc) > sam3InitialWood) sam3CollectedRouteWood = true
        if (ticks % 20 == 0) logger.info("SAM3 ACCESS tick={} position={} state={}", ticks, npc.snapshot().position, LumberjackService.status(server, sam3))
        val finished = job == null
        if (finished) {
            val position = npc.snapshot().position
            val dx = position.x - 1.5
            val dy = position.y - 68.5
            val dz = position.z - 8.5
            check(dx * dx + dy * dy + dz * dz <= 4.5 * 4.5) { "Sam3 stopped before reaching the chest: $position" }
            check(npc.inventoryContents().any { it.stack.itemId?.endsWith("_axe") == true }) { "Sam3 did not complete equipment preparation" }
            check(level.getBlockState(BlockPos(20, 68, 17)).isAir) { "Sam3 did not cut the blocking trunk in front of it" }
            check(sam3CollectedRouteWood && spruceWoodCount(npc) == sam3InitialWood) { "Sam3 did not collect and deposit its route wood" }
            check(server.commands.performPrefixedCommand(player.createCommandSourceStack(), "samcnpc effects $sam3 clear glowing") == 1)
        }
        sample = Sample(listOf(checkNotNull(level.getEntity(sam3)).id), finished, "world=$worldId ticks=$ticks Sam3ReachedChest=$finished routeWoodCollectedAndDeposited=${finished && sam3CollectedRouteWood} position=${npc.snapshot().position}")
    }

    private fun spruceWoodCount(npc: NpcFacade): Int = npc.inventoryContents()
        .filter { it.stack.itemId == "minecraft:spruce_log" }.sumOf { it.stack.count }

    @SubscribeEvent
    fun rendered(event: RenderLivingEvent.Post<*, *>) {
        if (enabled && event.entity.uuid in ids && event.entity.isCurrentlyGlowing) glowingFrames++
    }

    @SubscribeEvent
    fun frame(event: TickEvent.RenderTickEvent) {
        if (!enabled || event.phase != TickEvent.Phase.END) return
        val name = pendingScreenshot ?: return
        pendingScreenshot = null
        val minecraft = Minecraft.getInstance()
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.mainRenderTarget) { logger.info("Forest repair screenshot: {}", it.string) }
    }
}
