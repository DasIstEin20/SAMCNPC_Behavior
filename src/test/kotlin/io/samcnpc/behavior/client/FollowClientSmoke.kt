package io.samcnpc.behavior.client

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.gametest.FollowWorkScenario
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.model.PlayerModel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
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
import kotlin.math.atan2
import kotlin.math.hypot

@Mod.EventBusSubscriber(modid = SamcnpcBehavior.MOD_ID, value = [Dist.CLIENT])
object FollowClientSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.followSmoke")
    @Volatile private var worldId: String? = null
    @Volatile private var entityId = -1
    @Volatile private var outcome: String? = null
    @Volatile private var screenshotSaved = false
    private var scenario: FollowWorkScenario? = null
    private var ticks = 0
    private var frames = 0
    private var walkingFrames = 0
    private var screenshotRequested = false
    private var done = false

    @SubscribeEvent fun clientTick(event: TickEvent.ClientTickEvent) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return
        check(++ticks < 3200) { "follow client timed out" }
        val minecraft = Minecraft.getInstance()
        if (minecraft.screen is AccessibilityOnboardingScreen && minecraft.overlay == null) minecraft.screen?.onClose()
        if (worldId == null && minecraft.screen is TitleScreen && minecraft.overlay == null) {
            val id = "follow-smoke-${System.currentTimeMillis()}"
            worldId = id
            minecraft.options.pauseOnLostFocus = false
            minecraft.options.hideGui = true
            minecraft.options.renderDistance().set(6)
            minecraft.options.simulationDistance().set(8)
            minecraft.options.framerateLimit().set(60)
            val settings = LevelSettings(id, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT)
            minecraft.createWorldOpenFlows().createFreshLevel(id, settings, WorldOptions(0L, false, false),
                { registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() })
        }
        val player = minecraft.player
        val body = minecraft.level?.getEntity(entityId)
        if (player != null && body != null) {
            val dx = body.x - player.x
            val dz = body.z - player.z
            player.yRot = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
            player.xRot = (-Math.toDegrees(atan2(body.eyeY - player.eyeY, hypot(dx, dz)))).toFloat()
        }
        val result = outcome ?: return
        if (!screenshotRequested) {
            screenshotRequested = true
            Screenshot.grab(minecraft.gameDirectory, "follow-result.png", minecraft.mainRenderTarget) { screenshotSaved = true }
        }
        if (!screenshotSaved) return
        check(frames > 0 && walkingFrames >= 2) { "follow NPC was not actually rendered walking" }
        Files.writeString(Path.of("follow-smoke-result.txt"), "$result\nframes=$frames walking=$walkingFrames\nPASS\n")
        done = true
        minecraft.stop()
    }

    @SubscribeEvent fun serverTick(event: TickEvent.ServerTickEvent) {
        if (!enabled || outcome != null || event.phase != TickEvent.Phase.END) return
        val id = worldId ?: return
        val server = event.server
        if (server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString() != id) return
        val player = server.playerList.players.firstOrNull() ?: return
        var current = scenario
        if (current == null) {
            val level = server.overworld()
            level.dayTime = 6000
            level.gameRules.getRule(GameRules.RULE_DAYLIGHT).set(false, server)
            level.gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server)
            player.setGameMode(GameType.SPECTATOR)
            current = FollowWorkScenario(level, player, BlockPos(0, -61, 0))
            scenario = current
            entityId = checkNotNull(level.getEntity(current.npcUuid)).id
        }
        current.tick()
        outcome = current.outcome
    }

    @SubscribeEvent fun rendered(event: RenderLivingEvent.Post<*, *>) {
        if (!enabled || done || event.entity.id != entityId) return
        val model = event.renderer.model as? PlayerModel<*> ?: error("follow NPC lost player rendering")
        frames++
        if (kotlin.math.abs(model.rightLeg.xRot) > 0.1F) walkingFrames++
    }
}
