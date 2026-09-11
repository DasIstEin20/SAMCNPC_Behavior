package io.samcnpc.behavior.client

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcBlockContainerSlot
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import java.util.UUID
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
    private val variant = System.getProperty("samcnpc.toolSettingsVariant", "missing")
    private val bareHands = variant == "bare_hands"
    private var legacyEvidence = ""
    private var finiteHandle: NpcHandle? = null
    private var finiteAssigned = false
    private var sawMining = false
    private val finiteChest = BlockPos(20, -60, 0)
    private val finiteTrunk = BlockPos(24, -60, 0)
    private var expectedAxeDamage = 0
    init { require(variant in setOf("missing", "bare_hands", "durable")) }
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
        val settings = service.runtime(probe)?.snapshot() ?: return
        if (settings.ignoreMissingMiningTool != !bareHands || settings.bareHandsMiningOnly != bareHands) return
        val level = server.overworld()
        check(++ticks < 3000) { "Hand-work lumberjack timed out: ${handle?.let { LumberjackService.status(server, it.npcUuid) }}" }
        if (legacyEvidence.isNotEmpty()) {
            finiteTick(server, player.uuid)
            return
        }
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
        legacyEvidence = "Axe-less legacy lumberjack completed its two-log tree and deposited both real drops: ticks=$ticks"
    }

    private fun finiteTick(server: MinecraftServer, summoner: UUID) {
        val level = server.overworld()
        val service = CoreNpcApi.service(server)
        if (finiteHandle == null) {
            for (x in 18..28) for (z in -4..4) {
                level.setBlockAndUpdate(BlockPos(x, -61, z), Blocks.GRASS_BLOCK.defaultBlockState())
                for (y in -60..-49) level.setBlockAndUpdate(BlockPos(x, y, z), Blocks.AIR.defaultBlockState())
            }
            level.setBlockAndUpdate(finiteChest, Blocks.CHEST.defaultBlockState())
            for (y in 0..3) level.setBlockAndUpdate(finiteTrunk.above(y), Blocks.OAK_LOG.defaultBlockState())
            if (variant != "missing") {
                val axe = ItemStack(Items.IRON_AXE)
                if (variant == "durable") axe.damageValue = axe.maxDamage - 1
                expectedAxeDamage = axe.damageValue
                (level.getBlockEntity(finiteChest) as Container).setItem(0, axe)
            }
            finiteHandle = checkNotNull(service.summon(NpcSummonRequest(summoner, "FiniteToolSmoke", "minecraft:overworld",
                NpcPosition(20.5, -60.0, 1.5), 0.0F)).handle)
            return
        }
        val handle = checkNotNull(finiteHandle)
        val npc = service.runtime(handle) ?: return
        val snapshot = npc.snapshot()
        val destination = NpcBlockPosition(finiteChest.x, finiteChest.y, finiteChest.z)
        if (!finiteAssigned) {
            if (!snapshot.onGround) return
            // A carried axe must remain intact when Bare hands only explicitly overrides it.
            if (bareHands) check(npc.moveBlockContainerToInventory(NpcBlockContainerSlot(destination, 0), 1).status == NpcActionStatus.SUCCEEDED)
            val definition = LumberjackTaskDefinition(snapshot.dimensionId,
                WorkArea(WorkBox(NpcBlockPosition(22, -60, -3), NpcBlockPosition(28, -49, 3))),
                WoodSelection(listOf("samcnpc:oak")), destination, 4)
            check(TaskService.assign(server, npc, definition).status == NpcActionStatus.SUCCEEDED)
            finiteAssigned = true
        }
        if (snapshot.blockBreak != null) {
            val hand = npc.equipmentContents().mainHand
            if (variant == "durable") check(hand.itemId == "minecraft:iron_axe" && hand.damage == expectedAxeDamage) { "durability-disabled work did not use its preserved axe" }
            else check(hand.isEmpty) { "explicit hand-work setting used an axe" }
            sawMining = true
        }
        val record = checkNotNull(TaskStore.forServer(server).get(handle.npcUuid))
        if (!record.status.terminal) return
        val diagnostic = TaskService.status(server, handle.npcUuid).orEmpty()
        check(record.status == TaskStatus.COMPLETED && sawMining) { "finite tool-settings work failed: $diagnostic" }
        val chest = level.getBlockEntity(finiteChest) as Container
        val delivered = (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.OAK_LOG)) chest.getItem(it).count else 0 }
        check(delivered == 4 && (0..3).all { level.getBlockState(finiteTrunk.above(it)).isAir }) { diagnostic }
        val work = checkNotNull(record.primary.lumberjack)
        check(work.resources.delivered(WoodSelection(listOf("samcnpc:oak"))) == 4 && work.resources.entries.values.all { it.valid() })
        val axes = npc.inventoryContents().filter { it.stack.itemId == "minecraft:iron_axe" }
        if (variant == "missing") check(axes.isEmpty())
        else check(axes.size == 1 && axes.single().stack.count == 1 && axes.single().stack.damage == expectedAxeDamage) { "carried/supplied axe was lost, worn or duplicated" }
        check(npc.inventoryContents().none { it.stack.itemId == "minecraft:oak_log" })
        Files.writeString(Path.of("tool-settings-behavior-result.txt"),
            "$legacyEvidence\nFinite lumberjack: variant=$variant, delivered=$delivered, observedMining=$sawMining, preservedAxeDamage=$expectedAxeDamage, ticks=$ticks\nPASS\n")
        completed = true
    }
}
