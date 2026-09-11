package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.task.UnresolvedWorkStore
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarKernel
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarSession
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object ResumedPillarGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 500, batch = "pillar_restart_replaced")
    fun resumedCleanupPreservesAReplacedSupportInsteadOfMiningSomeoneElsesBlock(helper: GameTestHelper) = exercise(helper, true)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 500, batch = "pillar_restart_coherent")
    fun coherentBodyAndPillarReloadConservesTheActuallyPlacedAndRecoveredMaterial(helper: GameTestHelper) = exercise(helper, false)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 500, batch = "pillar_restart_unverified")
    fun aSavedPositionWithoutBlockIdentityCannotAuthorizeCleanup(helper: GameTestHelper) = exercise(helper, false, true)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 500, batch = "pillar_report_cleanup")
    fun incompleteCleanupReportSurvivesCancellationAndSavedDataReload(helper: GameTestHelper) = exercise(helper, true, serviceMode = "cleanup")

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 500, batch = "pillar_report_cancel")
    fun cancellingLoadedWorkRetainsTheRealUnremovedSupport(helper: GameTestHelper) = exercise(helper, false, serviceMode = "cancel")

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 500, batch = "pillar_report_failed")
    fun failureRetainsTheRealUnremovedSupportAfterItsJobEnds(helper: GameTestHelper) = exercise(helper, false, serviceMode = "failed")

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 500, batch = "pillar_report_unavailable")
    fun unknownReportVersionStopsWorkAndPreservesTheActiveCleanupObligation(helper: GameTestHelper) = exercise(helper, false, serviceMode = "unavailable")

    private fun exercise(helper: GameTestHelper, replace: Boolean, forgetIdentity: Boolean = false, serviceMode: String? = null) {
        for (x in 0..7) for (z in 0..7) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        helper.setBlock(BlockPos(3, 8, 3), Blocks.OAK_LOG)
        val chestPos = helper.absolutePos(BlockPos(2, 1, 2))
        helper.setBlock(BlockPos(2, 1, 2), Blocks.CHEST)
        val chest = helper.level.getBlockEntity(chestPos) as Container
        chest.setItem(0, ItemStack(Items.IRON_AXE))
        chest.setItem(1, ItemStack(Items.IRON_SHOVEL))
        val target = helper.absolutePos(BlockPos(3, 8, 3))
        val feet = helper.absolutePos(BlockPos(3, 1, 3))
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        var body = checkNotNull(type.create(helper.level)) as LivingEntity
        body.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, 0.0F, 0.0F)
        body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.OAK_LOG, 3))
        check(helper.level.addFreshEntity(body))
        var session: TemporaryPillarSession? = null
        var stage = 0
        var support: NpcBlockPosition? = null
        var finished = false
        helper.onEachTick {
            if (finished || stage == 1) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            var current = session
            if (current == null) {
                if (!body.onGround()) return@onEachTick
                for (slot in 0..1) check(npc.moveBlockContainerToInventory(NpcBlockContainerSlot(
                    NpcBlockPosition(chestPos.x, chestPos.y, chestPos.z), slot), 1).status == NpcActionStatus.SUCCEEDED)
                val begun = TemporaryPillarKernel.begin(npc, npc.worldView(), NpcBlockPosition(target.x, target.y, target.z))
                check(begun is TemporaryPillarKernel.PillarBeginResult.Started)
                current = begun.session
                session = current
            }
            if (stage == 0) {
                val progress = TemporaryPillarKernel.tick(npc, npc.worldView(), current)
                check(progress !is TemporaryPillarKernel.PillarProgress.Failed) { "fixture placement failed: $progress" }
                if (current.placedPositions.size == 1 && npc.snapshot().onGround) {
                    support = current.placedPositions.single()
                    check(npc.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:oak_log") it.stack.count else 0 } == 2)
                    TemporaryPillarKernel.beginCleanup(current)
                    if (forgetIdentity) current.placedBlockIds.clear()
                    npc.stopControl()
                    val store = checkpointStore(body.uuid, npc.snapshot().dimensionId, feet)
                    checkNotNull(store.jobFor(body.uuid)).pillarSession = current
                    val savedTask = store.save(CompoundTag())
                    val savedBody = body.saveWithoutId(CompoundTag())
                    body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK)
                    stage = 1
                    helper.runAfterDelay(2) {
                        val restored = checkNotNull(LumberjackDemoStore.load(savedTask).jobFor(savedTask.getList("jobs", 10).getCompound(0).getUUID("npcUuid")))
                        session = checkNotNull(restored.pillarSession)
                        if (serviceMode != null) {
                            if (serviceMode == "failed") restored.phase = LumberjackDemoPhase.DEPOSIT_WOOD
                            check(LumberjackDemoStore.forServer(helper.level.server).put(restored).status == NpcActionStatus.SUCCEEDED)
                        }
                        if (replace) {
                            val block = checkNotNull(support)
                            helper.level.setBlockAndUpdate(BlockPos(block.x, block.y, block.z), Blocks.DIRT.defaultBlockState())
                        }
                        body = checkNotNull(type.create(helper.level)) as LivingEntity
                        body.load(savedBody)
                        check(helper.level.addFreshEntity(body))
                        stage = 2
                    }
                }
            } else if (stage == 2 && npc.snapshot().onGround) {
                check(npc.snapshot().control == null && npc.snapshot().navigation == null) { "old Core input survived body reload" }
                val block = checkNotNull(support)
                if (serviceMode != null) {
                    val server = helper.level.server
                    if (serviceMode == "unavailable") {
                        val previous = UnresolvedWorkStore.forServer(server)
                        val raw = previous.save(CompoundTag()).apply { putInt("version", 99) }
                        val unknown = UnresolvedWorkStore.load(raw)
                        server.overworld().dataStorage.set(UnresolvedWorkStore.DATA_NAME, unknown)
                        try {
                            check(LumberjackService.tick(server, npc, npc.worldView()).status == NpcActionStatus.REJECTED)
                            check(LumberjackService.cancel(server, npc).status == NpcActionStatus.REJECTED)
                            check(checkNotNull(LumberjackDemoStore.forServer(server).jobFor(body.uuid)).pillarSession?.placedPositions == listOf(block))
                            check(unknown.save(CompoundTag()) == raw)
                            check(npc.snapshot().blockBreak == null && npc.snapshot().navigation == null)
                        } finally {
                            server.overworld().dataStorage.set(UnresolvedWorkStore.DATA_NAME, previous)
                        }
                        check(LumberjackService.cancel(server, npc).status == NpcActionStatus.SUCCEEDED)
                    } else if (serviceMode == "cancel") {
                        check(LumberjackService.cancel(server, npc).status == NpcActionStatus.SUCCEEDED)
                    } else {
                        val outcome = LumberjackService.tick(server, npc, npc.worldView())
                        if (serviceMode == "cleanup") {
                            check(outcome.status == NpcActionStatus.RUNNING) { "cleanup handoff failed: $outcome" }
                            check(checkNotNull(LumberjackDemoStore.forServer(server).jobFor(body.uuid)).pillarSession == null)
                            check(LumberjackService.cancel(server, npc).status == NpcActionStatus.SUCCEEDED)
                        } else check(outcome.status == NpcActionStatus.FAILED) { "missing chest did not fail: $outcome" }
                    }
                    check(LumberjackDemoStore.forServer(server).jobFor(body.uuid) == null)
                    val reports = UnresolvedWorkStore.forServer(server)
                    val recorded = reports.blocksFor(body.uuid).single()
                    check(recorded.position == block && recorded.blockId == "minecraft:oak_log")
                    val reloaded = UnresolvedWorkStore.load(reports.save(CompoundTag()))
                    server.overworld().dataStorage.set(UnresolvedWorkStore.DATA_NAME, reloaded)
                    check(LumberjackService.status(server, body.uuid)?.contains("unresolvedSupports=1") == true)
                    check(npc.snapshot().blockBreak == null)
                    check(helper.level.getBlockState(BlockPos(block.x, block.y, block.z)).`is`(if (replace) Blocks.DIRT else Blocks.OAK_LOG))
                    finished = true; body.discard(); helper.succeed()
                    return@onEachTick
                }
                val progress = TemporaryPillarKernel.tickCleanup(npc, npc.worldView(), current)
                if (replace || forgetIdentity) {
                    check(progress == TemporaryPillarKernel.PillarProgress.CleanupIncomplete) { "replaced support was adopted for mining: $progress" }
                    check(npc.snapshot().blockBreak == null) { "resumed cleanup started mining the replacement block" }
                    check(helper.level.getBlockState(BlockPos(block.x, block.y, block.z)).`is`(if (replace) Blocks.DIRT else Blocks.OAK_LOG))
                    check(current.placedPositions == listOf(block)) { "unresolved receipt was discarded" }
                    finished = true; body.discard(); helper.succeed()
                } else if (progress == TemporaryPillarKernel.PillarProgress.CleanupComplete) {
                    check(helper.level.getBlockState(BlockPos(block.x, block.y, block.z)).isAir)
                    val carried = npc.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:oak_log") it.stack.count else 0 }
                    val dropped = npc.worldView().queryEntities(NpcEntityQuery(npc.snapshot().position, 4.0, 16, typeIds = setOf("minecraft:item")))
                        .sumOf { observation ->
                            val stack = observation.itemStack
                            if (stack?.itemId == "minecraft:oak_log") stack.count else 0
                        }
                    check(carried + dropped == 3) { "restored cleanup created/lost material: carried=$carried dropped=$dropped" }
                    check(current.placedPositions.isEmpty())
                    finished = true; body.discard(); helper.succeed()
                }
            }
        }
    }

    private fun checkpointStore(uuid: java.util.UUID, dimension: String, feet: BlockPos): LumberjackDemoStore {
        val position = CompoundTag().apply { putInt("x", feet.x); putInt("y", feet.y); putInt("z", feet.z) }
        val entry = CompoundTag().apply {
            putUUID("npcUuid", uuid); putString("dimension", dimension)
            putString("phase", LumberjackDemoPhase.PILLAR_CLEANUP.name)
            put("chest", position.copy()); put("workCenter", position.copy())
        }
        return LumberjackDemoStore.load(CompoundTag().apply { putInt("version", 18); put("jobs", ListTag().apply { add(entry) }) })
    }
}
