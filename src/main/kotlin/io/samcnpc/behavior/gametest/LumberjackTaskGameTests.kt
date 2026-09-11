package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object LumberjackTaskGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2300, batch = "finite_wood_oak")
    fun oakMinimumFinishesItsTrunkAndProtectsSpeciesExclusionAndOuterBoundary(helper: GameTestHelper) = exercise(helper, 0)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2300, batch = "finite_wood_birch")
    fun birchSelectionLeavesEveryOakTrunkStanding(helper: GameTestHelper) = exercise(helper, 5)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2300, batch = "finite_wood_resume")
    fun partialWorkAndActualBodyReloadResumeWithoutRepeatingRemovedLogs(helper: GameTestHelper) = exercise(helper, 1)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2300, batch = "finite_wood_world_mismatch")
    fun restoredRemovedLogIsPreservedAndTaskFailsBeforeWorkReplay(helper: GameTestHelper) = exercise(helper, 2)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2300, batch = "finite_wood_body_mismatch")
    fun oldBodyInventoryCannotResumeNewWoodProgress(helper: GameTestHelper) = exercise(helper, 3)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2300, batch = "finite_wood_shortage")
    fun areaExhaustionReportsActualShortageWithoutTouchingExcludedTrees(helper: GameTestHelper) = exercise(helper, 6)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2800, batch = "finite_wood_support_cancel")
    fun cancellingAnActualScaffoldRetainsItsReportAndReleasesTaskChannels(helper: GameTestHelper) = exercise(helper, 4)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 6500, batch = "finite_wood_quantity")
    fun sixtyFourLogsAreActuallyDeliveredAcrossIrregularTerrain(helper: GameTestHelper) {
        val fixture = LumberjackQuantityFixture(helper.absolutePos(BlockPos.ZERO))
        fixture.prepare(helper.level)
        val body = newBody(helper)
        val spawn = fixture.position(12, 1, 0)
        body.moveTo(spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(body))
        var assigned = false; var done = false
        helper.onEachTick {
            if (done) return@onEachTick
            val server = helper.level.server
            val service = CoreNpcApi.service(server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!assigned) {
                if (!body.onGround()) return@onEachTick
                val definition = LumberjackTaskDefinition(npc.snapshot().dimensionId, fixture.area, WoodSelection(listOf("samcnpc:oak")), fixture.container, 64)
                check(TaskService.assign(server, npc, definition).status == NpcActionStatus.SUCCEEDED)
                assigned = true
            }
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            check(record.status != TaskStatus.FAILED) { TaskService.status(server, body.uuid).orEmpty() }
            if (record.status == TaskStatus.COMPLETED) {
                val state = checkNotNull(record.primary.lumberjack)
                check(fixture.verify(helper.level) == 64 && state.resources.delivered(WoodSelection(listOf("samcnpc:oak"))) == 64)
                check(state.resources.entries.values.all { it.valid() })
                check(state.job.pillarSession == null && UnresolvedWorkStore.forServer(server).blocksFor(body.uuid).isEmpty()) { "quantity task retained unresolved supports" }
                done = true; body.discard(); helper.succeed()
            }
        }
    }

    private fun exercise(helper: GameTestHelper, scenario: Int) {
        val fixture = fixture(helper, if (scenario == 4) 9 else 4)
        var body = fixture.first
        val chest = fixture.second
        val server = helper.level.server
        val wood = WoodSelection(listOf(if (scenario == 5) "samcnpc:birch" else "samcnpc:oak"))
        val definition = LumberjackTaskDefinition(helper.level.dimension().location().toString(),
            WorkArea(WorkBox(pos(helper, 0, 1, 0), pos(helper, 11, 12, 9)),
                listOf(WorkBox(pos(helper, 4, 1, 1), pos(helper, 4, 12, 1)))), wood, pos(helper, 2, 1, 3),
            if (scenario == 6) 12 else if (scenario == 4) 9 else 3, budget = TaskBudget(ticks = 2200))
        var stage = 0
        var done = false
        var age = 0
        var initialBody: CompoundTag? = null
        var resumedBudget: Int? = null
        var protectedReplacement: NpcBlockPosition? = null
        helper.onEachTick {
            if (done || stage == 2) return@onEachTick
            val service = CoreNpcApi.service(server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (stage == 0) {
                if (!body.onGround()) return@onEachTick
                initialBody = body.saveWithoutId(CompoundTag())
                val assigned = TaskService.assign(server, npc, definition)
                check(assigned.status == NpcActionStatus.SUCCEEDED) { "finite task assignment failed: $assigned" }
                check(TaskService.executeSelected(server, npc, npc.worldView()).status == NpcActionStatus.REJECTED) { "navigation-only action bypassed wood task channels" }
                check(LumberjackDemoStore.forServer(server).jobFor(body.uuid) == null) { "wood task created a second legacy primary" }
                stage = 1
            }
            age++
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            val state = checkNotNull(record.primary.lumberjack)
            if (age >= 2100) error("finite task timed out: ${TaskService.status(server, body.uuid)}")
            if (scenario in 1..3 && stage == 1 && state.observedRemovedBlocks.isNotEmpty() &&
                state.resources.entries.any { (id, count) -> wood.matches(id) && count.gathered > 0 }) {
                check(TaskService.pause(server, body.uuid).status == NpcActionStatus.SUCCEEDED)
                val checkpoint = TaskStore.forServer(server).save(CompoundTag())
                val savedBody = if (scenario == 3) checkNotNull(initialBody) else body.saveWithoutId(CompoundTag())
                resumedBudget = record.primary.remainingTicks
                val replaced = state.observedRemovedBlocks.first()
                body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK)
                stage = 2
                helper.runAfterDelay(2) {
                    if (scenario == 2) {
                        helper.level.setBlock(BlockPos(replaced.x, replaced.y, replaced.z), Blocks.OAK_LOG.defaultBlockState(), 3)
                        protectedReplacement = replaced
                    }
                    body = newBody(helper)
                    body.load(savedBody)
                    check(helper.level.addFreshEntity(body))
                    server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(checkpoint))
                    val restored = checkNotNull(TaskStore.forServer(server).get(body.uuid)) { "checkpoint rejected: ${TaskStore.forServer(server).problemFor(body.uuid)}" }
                    check(restored.id == record.id && restored.primary.remainingTicks == resumedBudget)
                    check(TaskService.resume(server, body.uuid).status == NpcActionStatus.SUCCEEDED)
                    stage = 3
                }
                return@onEachTick
            }
            if (scenario == 4 && state.job.pillarSession?.placedPositions?.isNotEmpty() == true) {
                val support = checkNotNull(state.job.pillarSession).placedPositions.first()
                check(TaskService.cancel(server, body.uuid).status == NpcActionStatus.SUCCEEDED)
                check(record.status == TaskStatus.CANCELLED && state.residuePreserved)
                val reports = UnresolvedWorkStore.forServer(server)
                check(reports.blocksFor(body.uuid).any { it.position == support }) { "actual support obligation was lost" }
                check(UnresolvedWorkStore.load(reports.save(CompoundTag())).blocksFor(body.uuid) == reports.blocksFor(body.uuid))
                check(TaskCodec.read(TaskCodec.write(record)).primary.lumberjack?.residuePreserved == true)
                check(npc.snapshot().navigation == null && npc.snapshot().blockBreak == null) { "cancel retained active Core channels" }
                done = true; body.discard(); helper.succeed()
                return@onEachTick
            }
            if (!record.status.terminal) return@onEachTick
            val diagnostic = TaskService.status(server, body.uuid).orEmpty()
            if (scenario == 2 || scenario == 3) {
                check(stage == 3 && record.status == TaskStatus.FAILED && record.reason == TaskReason.STATE_MISMATCH) { diagnostic }
                check(state.resources.delivered(wood) == 0) { "mismatched checkpoint replayed a delivery" }
                val replacement = protectedReplacement
                if (replacement != null) check(helper.level.getBlockState(BlockPos(replacement.x, replacement.y, replacement.z)).`is`(Blocks.OAK_LOG)) { "restored log was mined again" }
                if (scenario == 3) check(record.detail.contains("loaded inventory")) { diagnostic }
            } else {
                check(scenario != 4) { "scaffold task ended before producing a physical support: $diagnostic" }
                check(record.status == if (scenario == 6) TaskStatus.FAILED else TaskStatus.COMPLETED) { diagnostic }
                check(record.reason == if (scenario == 6) TaskReason.MISSING_RESOURCE else TaskReason.DELIVERED) { diagnostic }
                val item = if (scenario == 5) Items.BIRCH_LOG else Items.OAK_LOG
                val actual = (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(item)) chest.getItem(it).count else 0 }
                check(actual == 4 && state.resources.delivered(wood) == 4) { "quantity was not four physically delivered logs: actual=$actual $diagnostic" }
                check(state.resources.entries.values.all { it.valid() }) { "resource conservation failed" }
                check(TaskCodec.read(TaskCodec.write(record)).report() == record.report()) { "final checkpoint cannot reload" }
                val unselected = if (scenario == 5) BlockPos(6, 1, 4) else BlockPos(3, 1, 2)
                check(!helper.level.getBlockState(helper.absolutePos(unselected)).isAir) { "wrong wood species was cut" }
            }
            for (y in 1..4) {
                check(helper.level.getBlockState(helper.absolutePos(BlockPos(4, y, 1))).`is`(Blocks.OAK_LOG)) { "excluded tree was cut" }
                check(helper.level.getBlockState(helper.absolutePos(BlockPos(13, y, 4))).`is`(Blocks.OAK_LOG)) { "outside tree was cut" }
            }
            check(npc.snapshot().navigation == null && npc.snapshot().blockBreak == null) { "final task left active controls" }
            check(LumberjackDemoStore.forServer(server).jobFor(body.uuid) == null)
            done = true; body.discard(); helper.succeed()
        }
    }

    private fun fixture(helper: GameTestHelper, height: Int): Pair<LivingEntity, Container> {
        for (x in 0..15) for (z in 0..11) {
            helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
            for (y in 1..13) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
        }
        helper.setBlock(BlockPos(2, 1, 3), Blocks.CHEST)
        val chest = helper.level.getBlockEntity(helper.absolutePos(BlockPos(2, 1, 3))) as Container
        chest.setItem(0, ItemStack(Items.IRON_AXE)); chest.setItem(1, ItemStack(Items.IRON_SHOVEL))
        chest.setItem(2, ItemStack(Items.DIRT, 12)); chest.setChanged()
        for (y in 1..height) helper.setBlock(BlockPos(6, y, 4), Blocks.OAK_LOG)
        for (y in 1..4) {
            helper.setBlock(BlockPos(3, y, 2), Blocks.BIRCH_LOG)
            helper.setBlock(BlockPos(4, y, 1), Blocks.OAK_LOG)
            helper.setBlock(BlockPos(13, y, 4), Blocks.OAK_LOG)
        }
        val body = newBody(helper)
        val feet = helper.absolutePos(BlockPos(1, 1, 3))
        body.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, -90.0F, 0.0F)
        check(helper.level.addFreshEntity(body))
        return body to chest
    }
    private fun newBody(helper: GameTestHelper): LivingEntity {
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        return checkNotNull(type.create(helper.level)) as LivingEntity
    }
    private fun pos(helper: GameTestHelper, x: Int, y: Int, z: Int): NpcBlockPosition {
        val absolute = helper.absolutePos(BlockPos(x, y, z))
        return NpcBlockPosition(absolute.x, absolute.y, absolute.z)
    }
}
