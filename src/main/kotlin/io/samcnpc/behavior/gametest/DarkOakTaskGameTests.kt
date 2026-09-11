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

    private fun exercise(helper: GameTestHelper, seed: Long) {
        val scene = DarkOakWorkFixture(helper.absolutePos(BlockPos.ZERO), seed)
        scene.prepare(helper.level)
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
                check(TaskService.assign(server, npc, definition).status == NpcActionStatus.SUCCEEDED)
                assigned = true
            }
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            val work = checkNotNull(record.primary.lumberjack)
            work.job.pillarSession?.placedPositions?.let(supports::addAll)
            if (!record.status.terminal) return@onEachTick
            val diagnostic = "seed=$seed nativeLogs=${scene.logs.size} height=${scene.height} columns=${scene.columns} ${TaskService.status(server, body.uuid)}"
            val remaining = scene.logs.filterNot { helper.level.getBlockState(it).isAir }.map { it.subtract(scene.origin) }
            logger.info("Native dark oak terminal: {} remaining={}", diagnostic, remaining)
            check(record.status == TaskStatus.COMPLETED) { diagnostic }
            check(scene.verify(helper.level) == scene.logs.size && work.resources.delivered(wood) == scene.logs.size) { diagnostic }
            check(work.resources.entries.values.all { it.valid() } && work.resources.entries["minecraft:dark_oak_log"]?.retained == 0) { diagnostic }
            check(supports.all { helper.level.getBlockState(BlockPos(it.x, it.y, it.z)).isAir }) { "abandoned actual support: $diagnostic" }
            check(work.job.pillarSession == null && UnresolvedWorkStore.forServer(server).blocksFor(body.uuid).isEmpty()) { diagnostic }
            check(npc.snapshot().control == null && npc.snapshot().navigation == null && npc.snapshot().blockBreak == null) { diagnostic }
            logger.info("Native dark oak proof: {} supports={}", diagnostic, supports.size)
            done = true
            body.discard()
            helper.succeed()
        }
    }
}
