package io.samcnpc.behavior.client

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcHandle
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcSummonRequest
import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path

/** Companion to the GUI smoke: Behavior reads only Core's public physical snapshot. */
@Mod.EventBusSubscriber(modid = SamcnpcBehavior.MOD_ID, value = [Dist.CLIENT])
object ToolSettingsClientSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.configSmoke")
    private val chestPosition = BlockPos(6, -60, 3)
    private val trunk = BlockPos(12, -60, 0)
    private var handle: NpcHandle? = null
    private var ticks = 0
    private var started = false
    private var completed = false

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (!enabled || completed || event.phase != TickEvent.Phase.END) return
        val server = event.server
        val world = server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString()
        if (!world.startsWith("config-smoke-") || !world.endsWith("-a")) return
        val player = server.playerList.players.firstOrNull() ?: return
        val service = CoreNpcApi.service(server)
        val probe = service.loadedBySummoner(player.uuid).firstOrNull { it.displayName == "ConfigSmoke" } ?: return
        if (service.runtime(probe)?.snapshot()?.ignoreMissingMiningTool != true) return
        val level = server.overworld()
        check(++ticks < 3000) { "Hand-work lumberjack timed out: ${handle?.let { LumberjackService.status(server, it.npcUuid) }}" }
        if (handle == null) {
            level.setBlockAndUpdate(chestPosition, Blocks.CHEST.defaultBlockState())
            level.setBlockAndUpdate(trunk, Blocks.OAK_LOG.defaultBlockState())
            level.setBlockAndUpdate(trunk.above(), Blocks.OAK_LOG.defaultBlockState())
            handle = checkNotNull(service.summon(NpcSummonRequest(player.uuid, "HandWorkSmoke", "minecraft:overworld",
                NpcPosition(6.5, -60.0, 0.5), 0.0F)).handle)
            return
        }
        val current = checkNotNull(handle)
        val npc = service.runtime(current) ?: return
        if (!started && npc.snapshot().onGround) {
            check(LumberjackService.start(server, npc, npc.worldView()).status == NpcActionStatus.SUCCEEDED)
            started = true
        }
        if (!started || LumberjackDemoStore.forServer(server).jobFor(current.npcUuid) != null) return
        val chest = level.getBlockEntity(chestPosition) as Container
        val wood = (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.OAK_LOG)) chest.getItem(it).count else 0 }
        check(level.getBlockState(trunk).isAir && level.getBlockState(trunk.above()).isAir && wood == 2) {
            "Ignore missing tool failed full chest -> hand work -> deposit: wood=$wood ticks=$ticks status=${LumberjackService.status(server, current.npcUuid)}"
        }
        check(npc.inventoryContents().none { it.stack.itemId?.endsWith("_axe") == true || it.stack.itemId == "minecraft:oak_log" })
        Files.writeString(Path.of("tool-settings-behavior-result.txt"), "Axe-less lumberjack completed two-log tree and deposited both real drops: ticks=$ticks\nPASS\n")
        completed = true
    }
}
