package io.samcnpc.behavior.gametest

import com.mojang.logging.LogUtils
import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object DarkOakTaskGameTests {
    private val logger = LogUtils.getLogger()
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 6500, batch = "native_dark_oak_zero")
    fun nativeDarkOakTwoByTwoDeliversEveryActualLogWithoutCuttingOakOrBirch(helper: GameTestHelper) = exercise(helper, 0L)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 6500, batch = "native_dark_oak_seven")
    fun aSecondNativeDarkOakShapeUsesTheSameBoundedWorkPath(helper: GameTestHelper) = exercise(helper, 7L)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 6500, batch = "native_dark_oak_saved_corner")
    fun recordedBentTrunkCornerGeometryCannotRenewItsRouteWithoutProgress(helper: GameTestHelper) = exercise(helper, 7L, true)

    private fun exercise(helper: GameTestHelper, seed: Long, recordedCorner: Boolean = false) {
        val scene = DarkOakWorkFixture(helper.absolutePos(BlockPos.ZERO), seed)
        scene.prepare(helper.level)
        if (recordedCorner) {
            // Observed post-branch geometry from mixed-tool-facts-world's seed7 failure. This is
            // a controlled initial scene, not a claim that this fixture executed the earlier cuts.
            val removed=listOf(BlockPos(11,1,9),BlockPos(11,2,9),BlockPos(11,3,9),BlockPos(12,6,11),
                BlockPos(12,7,10),BlockPos(12,7,11),BlockPos(14,1,12),BlockPos(14,2,12),BlockPos(14,3,12),BlockPos(14,6,11))
                .map { scene.origin.offset(it) }.toSet()
            check(scene.logs.count { it in removed } == 3)
            for(position in removed) helper.level.setBlockAndUpdate(position,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
            scene.logs.removeAll(removed)
        }
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val start = scene.position(1, 1, 2)
        body.moveTo(start.x + 0.5, start.y.toDouble(), start.z + 0.5, -90.0F, 0.0F)
        check(helper.level.addFreshEntity(body))
        val server = helper.level.server
        val wood = WoodSelection(listOf("samcnpc:dark_oak"))
        val definition = LumberjackTaskDefinition(helper.level.dimension().location().toString(), scene.area, wood,
            scene.container, scene.logs.size, budget = TaskBudget(ticks = 6000))
        var assigned = false
        var done = false
        val supports = mutableSetOf<NpcBlockPosition>()
        helper.onEachTick {
            if (done) return@onEachTick
            val service = CoreNpcApi.service(server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!assigned) {
                if (!body.onGround()) return@onEachTick
                if (recordedCorner) {
                    body.moveTo(scene.origin.x+14.631506827794,scene.origin.y+1.0,scene.origin.z+13.2053526476869,-90.0F,0.0F)
                    val inventory=listOf(net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_AXE),
                        net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SHOVEL),
                        net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COARSE_DIRT,24),
                        net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DARK_OAK_LOG,3))
                    for(stack in inventory) {
                        val drop=net.minecraft.world.entity.item.ItemEntity(helper.level,body.x,body.y,body.z,stack)
                        drop.setNoPickUpDelay(); check(helper.level.addFreshEntity(drop))
                        check(npc.pickupItem(drop.uuid).status == NpcActionStatus.SUCCEEDED)
                    }
                    scene.chest(helper.level).clearContent()
                }
                check(TaskService.assign(server, npc, definition).status == NpcActionStatus.SUCCEEDED)
                if (recordedCorner) {
                    val job=checkNotNull(TaskStore.forServer(server).get(body.uuid)?.primary?.lumberjack).job
                    // Only the supplied initial approach is reproduced. The new task retains its
                    // own unspent scan and treats the three already carried logs as initial stock.
                    job.phase=io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase.TRAVEL_TO_LOG
                    job.trunkBasePosition=scene.position(12,1,12)
                    job.targetPosition=scene.position(12,2,12)
                    job.miningStance=scene.position(12,1,14)
                    job.initialTrunkTargetPending=false
                }
                assigned = true
            }
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            val work = checkNotNull(record.primary.lumberjack)
            work.job.pillarSession?.placedPositions?.let(supports::addAll)
            if (record.primary.remainingTicks % 100 == 0) {
                val snapshot = npc.snapshot()
                val job = work.job
                logger.info("NATIVE_DARK_TRACE seed={} recordedCorner={} remaining={} phase={} target={} stance={} access={} blocked={} rejected={} position={} grounded={} navigation={} control={} detail={}",
                    seed,recordedCorner,record.primary.remainingTicks,job.phase,job.targetPosition,job.miningStance,job.accessReturnTarget,job.blockedLogPosition,
                    job.rejectedMiningStances,snapshot.position,snapshot.onGround,snapshot.navigation,snapshot.control,record.detail)
            }
            if (!record.status.terminal) return@onEachTick
            val diagnostic = "seed=$seed recordedCorner=$recordedCorner nativeLogs=${scene.logs.size} height=${scene.height} columns=${scene.columns} ${TaskService.status(server, body.uuid)}"
            val remaining = scene.logs.filterNot { helper.level.getBlockState(it).isAir }.map { it.subtract(scene.origin) }
            logger.info("Native dark oak terminal: {} remaining={}", diagnostic, remaining)
            check(record.status == TaskStatus.COMPLETED) { diagnostic }
            check(scene.verify(helper.level) == scene.logs.size && work.resources.delivered(wood) == scene.logs.size) { diagnostic }
            check(work.resources.entries.values.all { it.valid() } && work.resources.entries["minecraft:dark_oak_log"]?.retained == if (recordedCorner) 3 else 0) { diagnostic }
            check(supports.all { helper.level.getBlockState(BlockPos(it.x, it.y, it.z)).isAir }) { "abandoned actual support: ${supports.filterNot { helper.level.getBlockState(BlockPos(it.x, it.y, it.z)).isAir }.map { it to helper.level.getBlockState(BlockPos(it.x, it.y, it.z)) }}; $diagnostic" }
            check(work.job.pillarSession == null && UnresolvedWorkStore.forServer(server).blocksFor(body.uuid).isEmpty()) { diagnostic }
            check(npc.snapshot().control == null && npc.snapshot().navigation == null && npc.snapshot().blockBreak == null) { diagnostic }
            logger.info("Native dark oak proof: {} supports={}", diagnostic, supports.size)
            done = true
            body.discard()
            helper.succeed()
        }
    }
}
