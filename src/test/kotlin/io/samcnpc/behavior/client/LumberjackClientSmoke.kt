package io.samcnpc.behavior.client

import com.mojang.logging.LogUtils
import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcHandle
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcSummonRequest
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.model.PlayerModel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.data.worldgen.features.TreeFeatures
import net.minecraft.util.RandomSource
import net.minecraft.world.Container
import net.minecraft.world.Difficulty
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path

/** Opt-in real client/integrated-server test; not part of the distributed Behavior JAR. */
@Mod.EventBusSubscriber(modid = SamcnpcBehavior.MOD_ID, value = [Dist.CLIENT])
object LumberjackClientSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.lumberjackSmoke")
    private val logger = LogUtils.getLogger()
    private val report = Path.of("lumberjack-smoke-result.txt")
    private const val WORK_Y = -60
    private val chestPosition = BlockPos(1, WORK_Y, 3)
    private data class Sample(val entityId: Int, val phase: String)
    @Volatile private var worldId: String? = null
    @Volatile private var sample: Sample? = null
    @Volatile private var result: String? = null

    // Server-owned state. Only immutable samples cross to the render/client thread.
    private var handle: NpcHandle? = null
    private var started = false
    private var serverTicks = 0
    private var completedTicks = 0
    private val trunks = mutableListOf<BlockPos>()
    private val initialWood = mutableMapOf<String, Int>()
    private val phases = mutableSetOf<String>()
    private val supports = mutableSetOf<io.samcnpc.core.api.NpcBlockPosition>()

    // Client-owned state.
    private var requestedWorld = false
    private var clientTicks = 0
    private var renderedFrames = 0
    private var walkingFrames = 0
    private var choppingFrames = 0
    private var screenshotRequested = false
    private var pendingScreenshot = false
    private var done = false

    @SubscribeEvent
    fun clientTick(event: TickEvent.ClientTickEvent) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return
        val minecraft = Minecraft.getInstance()
        check(++clientTicks < 7000) { "Lumberjack smoke timed out before a verified client result" }
        if (minecraft.screen is AccessibilityOnboardingScreen && minecraft.overlay == null) minecraft.screen?.onClose()
        if (!requestedWorld && minecraft.screen is TitleScreen && minecraft.overlay == null) {
            requestedWorld = true
            val id = "lumberjack-smoke-${System.currentTimeMillis()}"
            worldId = id
            minecraft.options.pauseOnLostFocus = false
            minecraft.options.hideGui = true
            minecraft.options.gamma().set(1.0)
            minecraft.options.renderDistance().set(4)
            minecraft.options.simulationDistance().set(6)
            val settings = LevelSettings("Lumberjack smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, GameRules(), WorldDataConfiguration.DEFAULT)
            minecraft.createWorldOpenFlows().createFreshLevel(id, settings, WorldOptions(0L, false, false),
                { registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() })
        }
        val outcome = result ?: return
        check(renderedFrames > 0 && walkingFrames >= 2 && choppingFrames >= 2 && screenshotRequested) {
            "No real animated lumberjack rendering: frames=$renderedFrames walk=$walkingFrames chop=$choppingFrames"
        }
        done = true
        val evidence = "$outcome\nframes=$renderedFrames walking=$walkingFrames chopping=$choppingFrames\nPASS\n"
        Files.writeString(report, evidence)
        logger.info("LUMBERJACK CLIENT SMOKE {}", evidence)
        minecraft.stop()
    }

    @SubscribeEvent
    fun serverTick(event: TickEvent.ServerTickEvent) {
        if (!enabled || result != null || event.phase != TickEvent.Phase.END) return
        val server = event.server
        val id = worldId ?: return
        if (server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString() != id) return
        val player = server.playerList.players.firstOrNull() ?: return
        val level = server.overworld()
        val service = CoreNpcApi.service(server)
        check(++serverTicks < 6000) { "Lumberjack client world timed out: ${handle?.let { LumberjackService.status(server, it.npcUuid) }}" }
        if (handle == null) {
            // Use the flat preset's real ground; a new floating platform can still have pending
            // skylight packets when the first animation screenshot is taken.
            for (x in -8..24) for (z in -8..10) level.setBlockAndUpdate(BlockPos(x, WORK_Y - 1, z), Blocks.GRASS_BLOCK.defaultBlockState())
            level.dayTime = 6000
            level.gameRules.getRule(GameRules.RULE_DAYLIGHT).set(false, server)
            level.gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server)
            val registry = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
            for ((index, key) in listOf(TreeFeatures.OAK, TreeFeatures.BIRCH).withIndex()) {
                val origin = BlockPos(8 + index * 8, WORK_Y, 0)
                check(registry.getHolderOrThrow(key).value().place(level, level.chunkSource.generator, RandomSource.create(810L + index), origin)) {
                    "Vanilla tree generation failed: $key"
                }
                for (y in WORK_Y..WORK_Y + 25) {
                    val position = BlockPos(origin.x, y, origin.z)
                    val blockId = level.getBlockState(position).block.builtInRegistryHolder().key().location().toString()
                    if (blockId.endsWith("_log")) {
                        trunks.add(position)
                        initialWood[blockId] = (initialWood[blockId] ?: 0) + 1
                    }
                }
            }
            check(initialWood.keys.size == 2 && trunks.size >= 8) { "Both real vanilla trees are required: $initialWood" }
            level.setBlockAndUpdate(chestPosition, Blocks.CHEST.defaultBlockState())
            val chest = level.getBlockEntity(chestPosition) as Container
            chest.setItem(0, ItemStack(Items.IRON_AXE))
            chest.setItem(1, ItemStack(Items.IRON_SHOVEL))
            chest.setItem(2, ItemStack(Items.IRON_PICKAXE))
            chest.setItem(3, ItemStack(Items.DIRT, 16))
            chest.setItem(4, ItemStack(Items.LEATHER_HELMET))
            chest.setChanged()
            player.setGameMode(GameType.SPECTATOR)
            val summoned = service.summon(NpcSummonRequest(player.uuid, "LumberjackSmoke", "minecraft:overworld", NpcPosition(0.5, WORK_Y.toDouble(), 0.5), 0.0F))
            check(summoned.result.status == NpcActionStatus.SUCCEEDED) { "Core summon failed: $summoned" }
            handle = checkNotNull(summoned.handle)
            return
        }
        val current = checkNotNull(handle)
        val npc = service.runtime(current) ?: return
        val body = level.getEntity(current.npcUuid) ?: return
        if (!started && npc.snapshot().onGround) {
            val start = LumberjackService.start(server, npc, npc.worldView())
            check(start.status == NpcActionStatus.SUCCEEDED) { "Lumberjack did not start: $start" }
            started = true
        }
        val job = LumberjackDemoStore.forServer(server).jobFor(current.npcUuid)
        job?.pillarSession?.placedPositions?.let(supports::addAll)
        val phase = job?.phase?.name ?: "IDLE"
        phases.add(phase)
        if (serverTicks % 200 == 0) logger.info("Lumberjack client progress: {}", LumberjackService.status(server, current.npcUuid))
        sample = Sample(body.id, phase)
        player.teleportTo(level, body.x + 4.0, body.y + 3.0, body.z - 7.0, 29.745F, 20.42F)
        if (!started || job != null) return
        check(trunks.all { level.getBlockState(it).isAir }) { "Lumberjack stopped with unfinished vanilla trunks: ${trunks.filter { !level.getBlockState(it).isAir }}" }
        check(supports.all { level.getBlockState(BlockPos(it.x, it.y, it.z)).isAir }) { "Lumberjack left a temporary scaffold: $supports" }
        val chest = level.getBlockEntity(chestPosition) as Container
        for ((itemId, expected) in initialWood) {
            val deposited = (0 until chest.containerSize).sumOf { slot ->
                val stack = chest.getItem(slot)
                if (stack.item.builtInRegistryHolder().key().location().toString() == itemId) stack.count else 0
            }
            check(deposited == expected) { "Wood conservation failed: $itemId deposited=$deposited expected=$expected" }
        }
        check(npc.inventoryContents().none { it.stack.itemId in initialWood }) { "Finished lumberjack retained or picked up undeposited wood" }
        // Keep observing after completion to expose late contact pickups, not just a one-tick pass.
        if (++completedTicks >= 40) result = "Vanilla oak+birch complete; wood=$initialWood ticks=$serverTicks phases=$phases"
    }

    @SubscribeEvent
    fun rendered(event: RenderLivingEvent.Post<*, *>) {
        if (!enabled || done) return
        val current = sample ?: return
        if (event.entity.id != current.entityId) return
        val model = event.renderer.model as? PlayerModel<*> ?: error("NPC lost its player renderer")
        renderedFrames++
        if (kotlin.math.abs(model.rightLeg.xRot) > 0.1F) walkingFrames++
        if (current.phase == "BREAK_LOG" && model.attackTime > 0.05F) {
            choppingFrames++
            if (!screenshotRequested) {
                screenshotRequested = true
                pendingScreenshot = true
            }
        }
    }

    @SubscribeEvent
    fun renderedFrame(event: TickEvent.RenderTickEvent) {
        if (!enabled || !pendingScreenshot || event.phase != TickEvent.Phase.END) return
        pendingScreenshot = false
        val minecraft = Minecraft.getInstance()
        Screenshot.grab(minecraft.gameDirectory, "lumberjack-chopping.png", minecraft.mainRenderTarget) {
            logger.info("Lumberjack client screenshot: {}", it.string)
        }
    }
}
