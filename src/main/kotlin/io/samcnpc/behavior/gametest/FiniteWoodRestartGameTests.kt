package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
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
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object FiniteWoodRestartGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3000, batch = "finite_scaffold_restart")
    fun coherentScaffoldAndBodyReloadFinishWithConservedWoodAndSupports(helper: GameTestHelper) = restart(helper, false)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3000, batch = "finite_scaffold_replaced")
    fun replacedSupportStopsResumedWorkAndKeepsItsCleanupObligation(helper: GameTestHelper) = restart(helper, true)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3000, batch = "finite_outer_tree_boundary")
    fun finishingATrunkCannotAuthorizeItsTopOutsideTheWorkBox(helper: GameTestHelper) = boundary(helper, false)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3000, batch = "finite_excluded_tree_top")
    fun finishingATrunkCannotAuthorizeItsExcludedTop(helper: GameTestHelper) = boundary(helper, true)

    private enum class Stage { ASSIGN, WORK, UNLOADED, RESUMED, DONE }

    private fun restart(helper: GameTestHelper, replace: Boolean) {
        val scene = Scene(helper, 9)
        val server = helper.level.server
        val wood = WoodSelection(listOf("samcnpc:oak"))
        val definition = LumberjackTaskDefinition(helper.level.dimension().location().toString(),
            scene.area, wood, scene.position(2, 1, 3), 9, budget = TaskBudget(ticks = 2800))
        var stage = Stage.ASSIGN
        var savedSupport: NpcBlockPosition? = null
        var savedRemoved = emptySet<NpcBlockPosition>()
        var savedTicks = 0
        helper.onEachTick {
            if (stage == Stage.DONE || stage == Stage.UNLOADED) return@onEachTick
            val service = CoreNpcApi.service(server)
            val npc = service.find(scene.body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (stage == Stage.ASSIGN) {
                if (!scene.body.onGround()) return@onEachTick
                check(TaskService.assign(server, npc, definition).status == NpcActionStatus.SUCCEEDED)
                stage = Stage.WORK
            }
            val record = checkNotNull(TaskStore.forServer(server).get(scene.body.uuid))
            val state = checkNotNull(record.primary.lumberjack)
            val pillar = state.job.pillarSession
            if (stage == Stage.WORK && pillar != null && pillar.placedPositions.isNotEmpty() && scene.body.onGround()) {
                check(TaskService.pause(server, scene.body.uuid).status == NpcActionStatus.SUCCEEDED)
                savedSupport = pillar.placedPositions.first()
                check(pillar.placedBlockIds[savedSupport] == "minecraft:dirt")
                check(checkNotNull(state.resources.entries["minecraft:dirt"]).consumed > 0)
                val savedTask = TaskStore.forServer(server).save(CompoundTag())
                val savedBody = scene.body.saveWithoutId(CompoundTag())
                savedRemoved = state.observedRemovedBlocks.toSet()
                savedTicks = record.primary.remainingTicks
                val taskId = record.id
                scene.body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK)
                stage = Stage.UNLOADED
                helper.runAfterDelay(2) {
                    if (replace) {
                        val position = checkNotNull(savedSupport)
                        helper.level.setBlockAndUpdate(BlockPos(position.x, position.y, position.z), Blocks.GOLD_BLOCK.defaultBlockState())
                    }
                    scene.body = scene.newBody()
                    scene.body.load(savedBody)
                    check(helper.level.addFreshEntity(scene.body))
                    server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(savedTask))
                    val restored = checkNotNull(TaskStore.forServer(server).get(scene.body.uuid)) {
                        "scaffold checkpoint rejected: ${TaskStore.forServer(server).problemFor(scene.body.uuid)}"
                    }
                    check(restored.id == taskId && restored.primary.remainingTicks == savedTicks)
                    check(restored.primary.lumberjack?.job?.pillarSession?.placedPositions?.contains(savedSupport) == true)
                    check(TaskService.resume(server, scene.body.uuid).status == NpcActionStatus.SUCCEEDED)
                    stage = Stage.RESUMED
                }
                return@onEachTick
            }
            if (stage == Stage.RESUMED && replace) {
                val position = checkNotNull(savedSupport)
                check(helper.level.getBlockState(BlockPos(position.x, position.y, position.z)).`is`(Blocks.GOLD_BLOCK)) { "replacement was mined" }
                check(state.observedRemovedBlocks == savedRemoved) { "new work ran before support reconciliation" }
            }
            if (!record.status.terminal) return@onEachTick
            val diagnostic = TaskService.status(server, scene.body.uuid).orEmpty()
            check(stage == Stage.RESUMED) { "task ended before physical scaffold reload: $diagnostic" }
            // Rejection on the first loaded observation spends no new execution tick.
            if (replace) {
                check(record.primary.remainingTicks == savedTicks) { "mismatch changed the saved budget: saved=$savedTicks $diagnostic" }
                check(record.status == TaskStatus.FAILED && record.reason == TaskReason.STATE_MISMATCH) { diagnostic }
                val report = UnresolvedWorkStore.forServer(server).blocksFor(scene.body.uuid)
                check(report.any { it.position == savedSupport && it.blockId == "minecraft:dirt" }) { "replaced scaffold obligation disappeared: $report" }
                check(state.residuePreserved)
            } else {
                check(record.primary.remainingTicks < savedTicks) { "resumed work reset/froze its budget: saved=$savedTicks $diagnostic" }
                check(record.status == TaskStatus.COMPLETED && record.reason == TaskReason.DELIVERED) { diagnostic }
                check(scene.stored(Items.OAK_LOG) == 9 && state.resources.delivered(wood) == 9) { diagnostic }
                check(state.job.pillarSession == null && UnresolvedWorkStore.forServer(server).blocksFor(scene.body.uuid).isEmpty())
                val physicalDirt = scene.stored(Items.DIRT) + (HarvestResources.inventoryCounts(npc)["minecraft:dirt"] ?: 0) + scene.dropped(Items.DIRT)
                check(physicalDirt == 12) { "scaffold material was lost/duplicated: $physicalDirt" }
            }
            check(state.resources.entries.values.all { it.valid() }) { diagnostic }
            check(npc.snapshot().navigation == null && npc.snapshot().blockBreak == null) { "terminal task retained control" }
            stage = Stage.DONE
            scene.body.discard()
            helper.succeed()
        }
    }

    private fun boundary(helper: GameTestHelper, exclude: Boolean) {
        val scene = Scene(helper, 8)
        val server = helper.level.server
        val top = WorkBox(scene.position(6, 5, 4), scene.position(6, 12, 4))
        val area = if (exclude) WorkArea(scene.area.bounds, listOf(top))
            else WorkArea(WorkBox(scene.position(0, 1, 0), scene.position(11, 4, 9)))
        val wood = WoodSelection(listOf("samcnpc:oak"))
        val definition = LumberjackTaskDefinition(helper.level.dimension().location().toString(), area, wood,
            scene.position(2, 1, 3), 8, budget = TaskBudget(ticks = 2800))
        var assigned = false
        var done = false
        helper.onEachTick {
            if (done) return@onEachTick
            val service = CoreNpcApi.service(server)
            val npc = service.find(scene.body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!assigned) {
                if (!scene.body.onGround()) return@onEachTick
                check(TaskService.assign(server, npc, definition).status == NpcActionStatus.SUCCEEDED)
                assigned = true
            }
            for (y in 5..8) check(helper.level.getBlockState(helper.absolutePos(BlockPos(6, y, 4))).`is`(Blocks.OAK_LOG)) { "protected tree top was cut at $y" }
            val record = checkNotNull(TaskStore.forServer(server).get(scene.body.uuid))
            val state = checkNotNull(record.primary.lumberjack)
            check(state.observedRemovedBlocks.all(area::contains))
            check(state.job.pillarSession?.placedPositions?.all(area::contains) != false)
            if (!record.status.terminal) return@onEachTick
            val diagnostic = TaskService.status(server, scene.body.uuid).orEmpty()
            check(record.status == TaskStatus.FAILED) { "unavailable quota was reported complete: $diagnostic" }
            val harvested = (1..4).count { helper.level.getBlockState(helper.absolutePos(BlockPos(6, it, 4))).isAir }
            check(harvested > 0) { "fixture never exercised trunk work: $diagnostic" }
            val physicalWood = scene.stored(Items.OAK_LOG) + (HarvestResources.inventoryCounts(npc)["minecraft:oak_log"] ?: 0) + scene.dropped(Items.OAK_LOG)
            check(physicalWood == harvested) { "boundary task lost/duplicated wood: physical=$physicalWood cut=$harvested $diagnostic" }
            check(state.resources.delivered(wood) == scene.stored(Items.OAK_LOG) && state.resources.entries.values.all { it.valid() })
            check(npc.snapshot().navigation == null && npc.snapshot().blockBreak == null)
            done = true
            scene.body.discard()
            helper.succeed()
        }
    }

    private class Scene(val helper: GameTestHelper, height: Int) {
        val area = WorkArea(WorkBox(position(0, 1, 0), position(11, 12, 9)))
        val chest: Container
        var body: LivingEntity
        init {
            for (x in 0..15) for (z in 0..11) {
                helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
                for (y in 1..13) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
            }
            helper.setBlock(BlockPos(2, 1, 3), Blocks.CHEST)
            chest = helper.level.getBlockEntity(helper.absolutePos(BlockPos(2, 1, 3))) as Container
            chest.setItem(0, ItemStack(Items.IRON_AXE))
            chest.setItem(1, ItemStack(Items.IRON_SHOVEL))
            chest.setItem(2, ItemStack(Items.DIRT, 12))
            chest.setChanged()
            for (y in 1..height) helper.setBlock(BlockPos(6, y, 4), Blocks.OAK_LOG)
            body = newBody()
            val spawn = helper.absolutePos(BlockPos(1, 1, 3))
            body.moveTo(spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, -90.0F, 0.0F)
            check(helper.level.addFreshEntity(body))
        }
        fun newBody(): LivingEntity {
            val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
            return checkNotNull(type.create(helper.level)) as LivingEntity
        }
        fun position(x: Int, y: Int, z: Int): NpcBlockPosition {
            val absolute = helper.absolutePos(BlockPos(x, y, z))
            return NpcBlockPosition(absolute.x, absolute.y, absolute.z)
        }
        fun stored(item: net.minecraft.world.item.Item): Int = (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(item)) chest.getItem(it).count else 0 }
        fun dropped(item: net.minecraft.world.item.Item): Int {
            val bounds = AABB(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(BlockPos(16, 14, 12)))
            return helper.level.getEntitiesOfClass(ItemEntity::class.java, bounds).sumOf { if (it.item.`is`(item)) it.item.count else 0 }
        }
    }
}
