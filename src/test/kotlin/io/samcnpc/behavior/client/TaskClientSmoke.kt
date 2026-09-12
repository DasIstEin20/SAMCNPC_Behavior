package io.samcnpc.behavior.client

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.gametest.LumberjackQuantityFixture
import io.samcnpc.behavior.gametest.DarkOakWorkFixture
import io.samcnpc.behavior.task.HarvestResources
import io.samcnpc.behavior.task.UnresolvedWorkStore
import io.samcnpc.behavior.task.WoodSelection
import io.samcnpc.behavior.task.TaskNavigator
import io.samcnpc.behavior.task.TaskService
import io.samcnpc.behavior.task.TaskStatus
import io.samcnpc.behavior.task.TaskStore
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcSummonRequest
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.model.PlayerModel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.Container
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.Difficulty
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
import java.util.UUID

/** A real integrated-server close/reopen, driven by the same manual commands a player uses. */
@Mod.EventBusSubscriber(modid = SamcnpcBehavior.MOD_ID, value = [Dist.CLIENT])
object TaskClientSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.taskSmoke")
    private val report = Path.of("task-smoke-result.txt")
    private val destination = NpcPosition(12.5, -60.0, 0.5)
    @Volatile private var worldId: String? = null
    @Volatile private var npcUuid: UUID? = null
    @Volatile private var reopenRequested = false
    @Volatile private var reopenStarted = false
    @Volatile private var outcome: String? = null
    @Volatile private var entityId = -1
    @Volatile private var screenshotSaved = false

    // Client state; only immutable values/flags above cross the server/render boundary.
    private var clientPhase = 0
    private var clientTicks = 0
    private var clientAge = 0
    private var frames = 0
    private var walkingFrames = 0
    private var pendingScreenshot = false
    private var screenshotRequested = false
    private var done = false

    // Server state survives the deliberate integrated-server restart only as test expectations.
    private var stage = 0
    private var serverTicks = 0
    private var stageAge = 0
    private var taskId: UUID? = null
    private var oldActionId: UUID? = null
    private var savedRemaining = 0
    private var settled: NpcPosition? = null
    private var sawNewAction = false
    private var navigationEvidence = ""
    private var deliveryEvidence = ""
    private var woodFixture: LumberjackQuantityFixture? = null
    private var darkOakFixture: DarkOakWorkFixture? = null
    private var woodEvidence = ""
    private var woodGatheredBeforeReload = 0
    private var woodRemovedBeforeReload = 0
    private var woodScreenshotRequested = false
    @Volatile private var woodScreenshotSaved = false
    private val deliveryChest = BlockPos(14, -60, 0)

    @SubscribeEvent
    fun clientTick(event: TickEvent.ClientTickEvent) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return
        check(++clientTicks < 11000) { "Task client smoke timed out at clientPhase=$clientPhase serverStage=$stage" }
        clientAge++
        val minecraft = Minecraft.getInstance()
        if (minecraft.screen is AccessibilityOnboardingScreen && minecraft.overlay == null) minecraft.screen?.onClose()
        if (clientPhase == 0 && minecraft.screen is TitleScreen && minecraft.overlay == null) {
            val id = "task-smoke-${System.currentTimeMillis()}"
            worldId = id
            clientPhase = 1
            minecraft.options.pauseOnLostFocus = false
            minecraft.options.hideGui = true
            minecraft.options.renderDistance().set(4)
            minecraft.options.simulationDistance().set(6)
            minecraft.options.framerateLimit().set(60)
            val settings = LevelSettings(id, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT)
            minecraft.createWorldOpenFlows().createFreshLevel(id, settings, WorldOptions(0L, false, false),
                { registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() })
        }
        if (clientPhase == 1 && reopenRequested) {
            reopenRequested = false
            minecraft.level?.disconnect()
            minecraft.clearLevel()
            minecraft.setScreen(TitleScreen())
            clientPhase = 2
            clientAge = 0
        }
        if (clientPhase == 2 && clientAge >= 20 && minecraft.singleplayerServer == null && minecraft.screen is TitleScreen && minecraft.overlay == null) {
            reopenStarted = true
            clientPhase = 1
            minecraft.createWorldOpenFlows().loadLevel(checkNotNull(minecraft.screen), checkNotNull(worldId))
        }
        val result = outcome ?: return
        if (!woodScreenshotRequested) {
            woodScreenshotRequested = true
            Screenshot.grab(minecraft.gameDirectory, "wood-task-result.png", minecraft.mainRenderTarget) { woodScreenshotSaved = true }
        }
        if (!screenshotSaved || !woodScreenshotSaved) return
        check(frames > 0 && walkingFrames >= 2 && sawNewAction) { "No real navigation/rendering after restart" }
        done = true
        Files.writeString(report, "$result\nframes=$frames walking=$walkingFrames\nPASS\n")
        minecraft.stop()
    }

    @SubscribeEvent
    fun serverTick(event: TickEvent.ServerTickEvent) {
        if (!enabled || outcome != null || event.phase != TickEvent.Phase.END) return
        val id = worldId ?: return
        val server = event.server
        if (server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString() != id) return
        val player = server.playerList.players.firstOrNull() ?: return
        check(++serverTicks < 9500) { "Task world timed out: ${npcUuid?.let { TaskService.status(server, it) }}" }
        val level = server.overworld()
        val service = CoreNpcApi.service(server)
        val uuid = npcUuid
        if (uuid == null) {
            for (x in -8..18) for (z in -8..8) level.setBlockAndUpdate(BlockPos(x, -61, z), Blocks.GRASS_BLOCK.defaultBlockState())
            level.dayTime = 6000
            level.gameRules.getRule(GameRules.RULE_DAYLIGHT).set(false, server)
            level.gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server)
            player.setGameMode(GameType.SPECTATOR)
            val summoned = service.summon(NpcSummonRequest(player.uuid, "TaskSmoke", "minecraft:overworld", NpcPosition(0.5, -60.0, 0.5), -90.0F))
            check(summoned.result.status == NpcActionStatus.SUCCEEDED)
            npcUuid = checkNotNull(summoned.handle).npcUuid
            return
        }
        val handle = service.find(uuid) ?: return
        val npc = service.runtime(handle) ?: return
        val body = level.getEntity(uuid) ?: return
        if (!npc.snapshot().onGround) return
        entityId = body.id
        player.teleportTo(level, body.x + 4.0, body.y + 3.0, body.z - 7.0, 29.745F, 20.42F)
        stageAge++
        fun command(tail: String) {
            check(server.commands.performPrefixedCommand(player.createCommandSourceStack(), "samcnpc behavior task $tail") == 1) { "Task command failed: $tail" }
        }
        if (stage == 0) {
            command("assign $uuid navigate 12.5 -60 0.5 1200")
            val record = checkNotNull(TaskStore.forServer(server).get(uuid))
            taskId = record.id
            stage = 1
            stageAge = 0
        } else if (stage == 1 && stageAge >= 8 && npc.snapshot().navigation != null) {
            oldActionId = npc.snapshot().navigation?.actionId
            command("pause $uuid")
            val record = checkNotNull(TaskStore.forServer(server).get(uuid))
            check(record.status == TaskStatus.PAUSED && npc.snapshot().navigation == null && npc.snapshot().control == null)
            savedRemaining = record.primary.remainingTicks
            check(server.saveEverything(false, true, true)) { "World save failed" }
            stage = 2
            stageAge = 0
            reopenRequested = true
        } else if (stage == 2 && reopenStarted) {
            val record = checkNotNull(TaskStore.forServer(server).get(uuid)) { "Task missing after real world reload" }
            check(record.id == taskId && record.status == TaskStatus.PAUSED && record.primary.remainingTicks == savedRemaining)
            check(npc.snapshot().navigation == null && npc.snapshot().control == null) { "A Core route/input survived world reload" }
            if (stageAge >= 10 && settled == null) settled = npc.snapshot().position
            if (stageAge >= 30) {
                check(TaskNavigator.distanceSquared(checkNotNull(settled), npc.snapshot().position) < 0.0025) { "Paused NPC drifted after reload" }
                command("status $uuid")
                command("resume $uuid")
                stage = 3
            }
        } else if (stage == 3) {
            val navigation = npc.snapshot().navigation
            if (navigation != null) {
                check(navigation.actionId != oldActionId) { "Restored task reused an old Core action" }
                sawNewAction = true
            }
            val record = checkNotNull(TaskStore.forServer(server).get(uuid))
            check(record.status != TaskStatus.FAILED) { TaskService.status(server, uuid).orEmpty() }
            if (record.status == TaskStatus.COMPLETED) {
                check(record.id == taskId && TaskNavigator.distanceSquared(npc.snapshot().position, destination) <= 0.75 * 0.75)
                check(npc.snapshot().navigation == null && npc.snapshot().control == null)
                navigationEvidence = "Navigation $taskId: assign -> movement -> pause -> save/close/reopen -> preserved budget $savedRemaining -> resume -> actual arrival"
                level.setBlockAndUpdate(deliveryChest, Blocks.CHEST.defaultBlockState())
                val chest = level.getBlockEntity(deliveryChest) as Container
                for (slot in 0 until chest.containerSize) chest.setItem(slot, ItemStack(Items.STONE, 64))
                chest.setItem(0, ItemStack(Items.OAK_LOG, 58))
                chest.setChanged()
                (body as LivingEntity).setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.OAK_LOG, 16))
                command("assign $uuid deliver 14 -60 0 minecraft:oak_log 12 4")
                taskId = checkNotNull(TaskStore.forServer(server).get(uuid)).id
                stage = 4
                stageAge = 0
            }
        } else if (stage == 4) {
            val record = checkNotNull(TaskStore.forServer(server).get(uuid))
            check(!record.status.terminal) { TaskService.status(server, uuid).orEmpty() }
            if (record.primary.transport?.ledger?.delivered == 6) {
                check((body as LivingEntity).mainHandItem.count == 10)
                command("pause $uuid")
                savedRemaining = record.primary.remainingTicks
                check(server.saveEverything(false, true, true))
                stage = 5
                stageAge = 0
                reopenStarted = false
                reopenRequested = true
            }
        } else if (stage == 5 && reopenStarted) {
            val record = checkNotNull(TaskStore.forServer(server).get(uuid))
            val chest = level.getBlockEntity(deliveryChest) as Container
            check(record.id == taskId && record.status == TaskStatus.PAUSED && record.primary.remainingTicks == savedRemaining)
            check(record.primary.transport?.ledger?.delivered == 6 && record.primary.transport?.ledger?.transferCount == 1)
            check((body as LivingEntity).mainHandItem.count == 10 && chest.getItem(0).count == 64)
            if (stageAge >= 25) {
                chest.setItem(1, ItemStack.EMPTY)
                chest.setChanged()
                command("status $uuid")
                command("resume $uuid")
                stage = 6
            }
        } else if (stage == 6) {
            val record = checkNotNull(TaskStore.forServer(server).get(uuid))
            check(record.status != TaskStatus.FAILED) { TaskService.status(server, uuid).orEmpty() }
            if (record.status == TaskStatus.COMPLETED) {
                val chest = level.getBlockEntity(deliveryChest) as Container
                val total = (0 until chest.containerSize).sumOf { slot ->
                    val stack = chest.getItem(slot)
                    if (stack.`is`(Items.OAK_LOG)) stack.count else 0
                }
                check(record.id == taskId && record.primary.transport?.ledger?.delivered == 12 && record.primary.transport?.ledger?.transferCount == 2)
                check(checkNotNull(record.primary.transport).ledger.valid())
                check((body as LivingEntity).mainHandItem.count == 4 && total == 70)
                deliveryEvidence = "Delivery $taskId: actual partial 6 -> pause -> second save/close/reopen -> confirmed cargo transfer 1 preserved -> resume -> actual 12 delivered, 4 reserved, chest 70"
                val fixture = LumberjackQuantityFixture(BlockPos(0, -61, 20))
                fixture.prepare(level)
                woodFixture = fixture
                command("assign $uuid lumberjack 0 -60 24 23 -51 46 14 -60 20 samcnpc:oak 64 6000 exclude 0 -60 24 0 -51 24")
                taskId = checkNotNull(TaskStore.forServer(server).get(uuid)).id
                stage = 7
                stageAge = 0
            }
        } else if (stage == 7) {
            val record = checkNotNull(TaskStore.forServer(server).get(uuid))
            check(!record.status.terminal) { TaskService.status(server, uuid).orEmpty() }
            val state = checkNotNull(record.primary.lumberjack)
            val gathered = state.resources.entries["minecraft:oak_log"]?.gathered ?: 0
            if (gathered >= 16 && state.job.pillarSession == null) {
                command("pause $uuid")
                woodGatheredBeforeReload = gathered
                woodRemovedBeforeReload = state.observedRemovedBlocks.size
                savedRemaining = record.primary.remainingTicks
                check(server.saveEverything(false, true, true))
                stage = 8
                stageAge = 0
                reopenStarted = false
                reopenRequested = true
            }
        } else if (stage == 8 && reopenStarted) {
            val record = checkNotNull(TaskStore.forServer(server).get(uuid))
            val state = checkNotNull(record.primary.lumberjack)
            check(record.id == taskId && record.status == TaskStatus.PAUSED && record.primary.remainingTicks == savedRemaining)
            check((state.resources.entries["minecraft:oak_log"]?.gathered ?: 0) >= woodGatheredBeforeReload)
            check(state.observedRemovedBlocks.size == woodRemovedBeforeReload)
            check(npc.snapshot().navigation == null && npc.snapshot().blockBreak == null)
            if (stageAge >= 25) {
                command("status $uuid")
                command("resume $uuid")
                stage = 9
            }
        } else if (stage == 9) {
            val record = checkNotNull(TaskStore.forServer(server).get(uuid))
            check(record.status != TaskStatus.FAILED) { TaskService.status(server, uuid).orEmpty() }
            if (record.status == TaskStatus.COMPLETED) {
                val state = checkNotNull(record.primary.lumberjack)
                val actual = checkNotNull(woodFixture).verify(level)
                check(actual == 64 && state.resources.delivered(WoodSelection(listOf("samcnpc:oak"))) == 64)
                check(state.resources.entries["minecraft:oak_log"]?.retained == 4) { "initial carried wood reserve was not retained" }
                check(state.resources.entries.values.all { it.valid() })
                check(npc.snapshot().navigation == null && npc.snapshot().blockBreak == null)
                woodEvidence = "$navigationEvidence\n$deliveryEvidence\nWood $taskId: manual oak/box/exclusion/minimum command -> $woodGatheredBeforeReload gathered -> third save/close/reopen -> $woodRemovedBeforeReload removed positions and $savedRemaining budget retained -> 64 logs actually delivered on irregular terrain, initial 4 retained; ${state.resources.describe(WoodSelection(listOf("samcnpc:oak")), 64)}; ticks=$serverTicks"
                val fixture = DarkOakWorkFixture(BlockPos(35, -61, 20), 7L)
                fixture.prepare(level)
                darkOakFixture = fixture
                val min = fixture.area.bounds.min
                val max = fixture.area.bounds.max
                val chest = fixture.container
                command("assign $uuid lumberjack ${min.x} ${min.y} ${min.z} ${max.x} ${max.y} ${max.z} ${chest.x} ${chest.y} ${chest.z} samcnpc:dark_oak ${fixture.logs.size} 6000")
                taskId = checkNotNull(TaskStore.forServer(server).get(uuid)).id
                stage = 10
                stageAge = 0
            }
        } else if (stage == 10) {
            val record = checkNotNull(TaskStore.forServer(server).get(uuid))
            check(record.status != TaskStatus.FAILED) { TaskService.status(server, uuid).orEmpty() }
            if (record.status == TaskStatus.COMPLETED) {
                val fixture = checkNotNull(darkOakFixture)
                val state = checkNotNull(record.primary.lumberjack)
                val selection = WoodSelection(listOf("samcnpc:dark_oak"))
                check(fixture.verify(level) == fixture.logs.size && state.resources.delivered(selection) == fixture.logs.size)
                check(state.resources.entries.values.all { it.valid() } && state.resources.entries["minecraft:dark_oak_log"]?.retained == 0)
                check(HarvestResources.inventoryCounts(npc)["minecraft:oak_log"] == 4) { "dark oak selection consumed the carried oak reserve" }
                check(state.job.pillarSession == null && UnresolvedWorkStore.forServer(server).blocksFor(uuid).isEmpty())
                check(npc.snapshot().navigation == null && npc.snapshot().blockBreak == null && npc.snapshot().control == null)
                outcome = "$woodEvidence\nNative dark oak $taskId: manual species command -> ${fixture.logs.size} actual logs delivered; native height=${fixture.height} columns=${fixture.columns}; oak/birch protected, carried oak 4 retained, no scaffold residue; ${state.resources.describe(selection, fixture.logs.size)}; ticks=$serverTicks"
            }
        }
    }

    @SubscribeEvent
    fun rendered(event: RenderLivingEvent.Post<*, *>) {
        if (!enabled || done || event.entity.id != entityId) return
        val model = event.renderer.model as? PlayerModel<*> ?: error("Task NPC lost player rendering")
        frames++
        if (kotlin.math.abs(model.rightLeg.xRot) > 0.1F) {
            walkingFrames++
            if (reopenStarted && !screenshotRequested) {
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
        Screenshot.grab(minecraft.gameDirectory, "task-after-restart.png", minecraft.mainRenderTarget) { screenshotSaved = true }
    }
}
