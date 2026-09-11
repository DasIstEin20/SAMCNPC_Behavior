package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object FiniteWoodAdversityGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3200, batch = "finite_full_inventory")
    fun fullInventoryStopsWithConservedPhysicalDropsAndExistingStock(helper: GameTestHelper) = exercise(helper, Case.FULL_INVENTORY)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3200, batch = "finite_full_storage")
    fun fullStorageRetainsActualHarvestWithoutInventingDeliveredQuantity(helper: GameTestHelper) = exercise(helper, Case.FULL_STORAGE)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3200, batch = "finite_missing_axe")
    fun missingAxeStopsBeforeWorkUnderNormalCoreSettings(helper: GameTestHelper) = exercise(helper, Case.MISSING_AXE)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3200, batch = "finite_last_durability")
    fun finalAxeDurabilityAllowsOneRealBreakAndNoFabricatedReplacement(helper: GameTestHelper) = exercise(helper, Case.LAST_DURABILITY)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 6500, batch = "finite_wood_only_scaffold")
    fun missingPrimarySupportsUsesAndRecoversActualHarvestedWood(helper: GameTestHelper) = exercise(helper, Case.NO_SUPPORTS)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3200, batch = "finite_removed_target")
    fun disappearingActiveTargetCannotProduceASecondDropOrFalseQuota(helper: GameTestHelper) = exercise(helper, Case.REMOVED_TARGET)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3200, batch = "finite_destroyed_storage")
    fun destroyedDestinationAfterPickupEndsWithCarriedResourcesAndBoundedRetries(helper: GameTestHelper) = exercise(helper, Case.DESTROYED_STORAGE)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 3200, batch = "finite_blocked_approach")
    fun blockedApproachNeverAuthorizesMiningTheEnclosingBedrock(helper: GameTestHelper) = exercise(helper, Case.BLOCKED_APPROACH)

    private enum class Case { FULL_INVENTORY, FULL_STORAGE, MISSING_AXE, LAST_DURABILITY, NO_SUPPORTS, REMOVED_TARGET, DESTROYED_STORAGE, BLOCKED_APPROACH }

    private fun exercise(helper: GameTestHelper, scenario: Case) {
        val height = if (scenario == Case.NO_SUPPORTS) 9 else 4
        val scene = Scene(helper, height, scenario == Case.BLOCKED_APPROACH)
        val server = helper.level.server
        val wood = WoodSelection(listOf("samcnpc:oak"))
        val duration = if (scenario == Case.NO_SUPPORTS) 6000 else 2800
        val definition = LumberjackTaskDefinition(helper.level.dimension().location().toString(),
            WorkArea(WorkBox(scene.position(0, 1, 0), scene.position(11, 12, 9))), wood,
            scene.chestPosition, height, budget = TaskBudget(ticks = duration))
        var assigned = false
        var changedWorld = false
        var done = false
        var removedByFixture = 0
        val actualSupports = mutableSetOf<NpcBlockPosition>()
        helper.onEachTick {
            if (done) return@onEachTick
            val service = CoreNpcApi.service(server)
            val npc = service.find(scene.body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!assigned) {
                if (!scene.body.onGround()) return@onEachTick
                check(!npc.snapshot().ignoreMissingMiningTool && !npc.snapshot().bareHandsMiningOnly) { "adversity fixture requires normal tool settings" }
                scene.stock(npc, scenario)
                check(TaskService.assign(server, npc, definition).status == NpcActionStatus.SUCCEEDED)
                assigned = true
            }
            val record = checkNotNull(TaskStore.forServer(server).get(scene.body.uuid))
            val state = checkNotNull(record.primary.lumberjack)
            state.job.pillarSession?.placedPositions?.let(actualSupports::addAll)
            if (!changedWorld && scenario == Case.REMOVED_TARGET) {
                val breaking = npc.snapshot().blockBreak
                if (breaking != null) {
                    val target = breaking.position
                    check(helper.level.getBlockState(BlockPos(target.x, target.y, target.z)).`is`(Blocks.OAK_LOG))
                    helper.level.setBlockAndUpdate(BlockPos(target.x, target.y, target.z), Blocks.AIR.defaultBlockState())
                    removedByFixture = 1
                    changedWorld = true
                }
            }
            if (!changedWorld && scenario == Case.DESTROYED_STORAGE && (state.resources.entries["minecraft:oak_log"]?.gathered ?: 0) > 0) {
                check((0 until scene.chest.containerSize).all { scene.chest.getItem(it).isEmpty }) { "storage removal would hide supplied resources" }
                val chest = scene.chestPosition
                helper.level.setBlockAndUpdate(BlockPos(chest.x, chest.y, chest.z), Blocks.AIR.defaultBlockState())
                changedWorld = true
            }
            if (!record.status.terminal) return@onEachTick
            val diagnostic = TaskService.status(server, scene.body.uuid).orEmpty()
            val counts = HarvestResources.inventoryCounts(npc)
            val storedWood = if (scenario == Case.DESTROYED_STORAGE && changedWorld) 0 else scene.stored(Items.OAK_LOG)
            val removed = (1..height).count { helper.level.getBlockState(helper.absolutePos(BlockPos(6, it, 4))).isAir }
            val physicalWood = storedWood + (counts["minecraft:oak_log"] ?: 0) + scene.dropped(Items.OAK_LOG)
            check(physicalWood == removed - removedByFixture) { "physical wood lost/duplicated: actual=$physicalWood removed=$removed external=$removedByFixture $diagnostic" }
            check(state.resources.entries.values.all { it.valid() }) { "resource ledger is not conserved: $diagnostic" }
            check(state.resources.delivered(wood) == storedWood) { "delivery differs from actual storage: $diagnostic" }
            check(npc.snapshot().navigation == null && npc.snapshot().blockBreak == null && npc.snapshot().control == null) { "terminal task retained control: $diagnostic" }
            when (scenario) {
                Case.FULL_INVENTORY -> {
                    check(record.status == TaskStatus.FAILED && storedWood == 0) { diagnostic }
                    check(counts["minecraft:stone"] == 33 * 64) { "full inventory lost initial stock" }
                    check(npc.inventoryContents().count { !it.stack.isEmpty } == 36)
                }
                Case.FULL_STORAGE -> {
                    check(record.status == TaskStatus.FAILED && storedWood == 0 && (counts["minecraft:oak_log"] ?: 0) > 0) { diagnostic }
                    check(scene.stored(Items.STONE) == 27 * 64) { "full storage was overwritten" }
                    check(record.detail.contains("no room")) { diagnostic }
                }
                Case.MISSING_AXE -> {
                    check(record.status == TaskStatus.FAILED && removed == 0 && record.detail.contains("no axe")) { diagnostic }
                    check(counts["minecraft:iron_axe"] == null && scene.stored(Items.IRON_AXE) == 0)
                }
                Case.LAST_DURABILITY -> {
                    check(record.status == TaskStatus.FAILED && removed == 1) { "last durability did not permit exactly one break: $diagnostic" }
                    check(counts["minecraft:iron_axe"] == null && scene.stored(Items.IRON_AXE) == 0 && scene.dropped(Items.IRON_AXE) == 0)
                    check(state.resources.entries["minecraft:iron_axe"]?.lost == 1) { "destroyed tool was not accounted: $diagnostic" }
                }
                Case.NO_SUPPORTS -> {
                    check(record.status == TaskStatus.COMPLETED && storedWood == 9) { diagnostic }
                    check(actualSupports.isNotEmpty() && (state.resources.entries["minecraft:oak_log"]?.consumed ?: 0) > 0) { "fixture never used real wood supports" }
                    check(state.job.pillarSession == null && UnresolvedWorkStore.forServer(server).blocksFor(scene.body.uuid).isEmpty()) { "finite wood recovery abandoned supports: $diagnostic" }
                    check(actualSupports.all { helper.level.getBlockState(BlockPos(it.x, it.y, it.z)).isAir })
                }
                Case.REMOVED_TARGET -> check(changedWorld && record.status == TaskStatus.FAILED && storedWood < height) { diagnostic }
                Case.DESTROYED_STORAGE -> {
                    check(changedWorld && record.status == TaskStatus.FAILED && record.reason == TaskReason.RETRY_LIMIT) { diagnostic }
                    check(record.totalFailures == definition.budget.attempts && (counts["minecraft:oak_log"] ?: 0) > 0) { diagnostic }
                }
                Case.BLOCKED_APPROACH -> {
                    check(record.status == TaskStatus.FAILED && removed == 0) { diagnostic }
                    check(scene.barriers.all { helper.level.getBlockState(it).`is`(Blocks.BEDROCK) })
                }
            }
            done = true
            scene.body.discard()
            helper.succeed()
        }
    }

    private class Scene(val helper: GameTestHelper, height: Int, blocked: Boolean) {
        val chestPosition = position(2, 1, 3)
        val chest: Container
        val body: LivingEntity
        val barriers = mutableListOf<BlockPos>()
        init {
            for (x in 0..15) for (z in 0..11) {
                helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
                for (y in 1..13) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
            }
            helper.setBlock(BlockPos(2, 1, 3), Blocks.CHEST)
            chest = helper.level.getBlockEntity(helper.absolutePos(BlockPos(2, 1, 3))) as Container
            for (y in 1..height) helper.setBlock(BlockPos(6, y, 4), Blocks.OAK_LOG)
            if (blocked) for (x in 5..7) for (z in 3..5) if (x != 6 || z != 4) for (y in 1..12) {
                val block = helper.absolutePos(BlockPos(x, y, z))
                helper.level.setBlockAndUpdate(block, Blocks.BEDROCK.defaultBlockState())
                barriers.add(block)
            }
            val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
            body = checkNotNull(type.create(helper.level)) as LivingEntity
            val spawn = helper.absolutePos(BlockPos(1, 1, 3))
            body.moveTo(spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, -90.0F, 0.0F)
            check(helper.level.addFreshEntity(body))
        }
        fun stock(npc: NpcFacade, scenario: Case) {
            if (scenario == Case.FULL_INVENTORY || scenario == Case.FULL_STORAGE) {
                take(npc, ItemStack(Items.IRON_AXE))
                take(npc, ItemStack(Items.IRON_SHOVEL))
                take(npc, ItemStack(Items.DIRT, 12))
                if (scenario == Case.FULL_INVENTORY) repeat(33) { take(npc, ItemStack(Items.STONE, 64)) }
                if (scenario == Case.FULL_STORAGE) for (slot in 0 until chest.containerSize) chest.setItem(slot, ItemStack(Items.STONE, 64))
            } else {
                if (scenario != Case.MISSING_AXE) {
                    val axe = ItemStack(Items.IRON_AXE)
                    if (scenario == Case.LAST_DURABILITY) axe.damageValue = axe.maxDamage - 1
                    chest.setItem(0, axe)
                }
                if (scenario != Case.NO_SUPPORTS) {
                    chest.setItem(1, ItemStack(Items.IRON_SHOVEL))
                    chest.setItem(2, ItemStack(Items.DIRT, 12))
                }
            }
            chest.setChanged()
        }
        private fun take(npc: NpcFacade, stack: ItemStack) {
            chest.setItem(26, stack)
            chest.setChanged()
            check(npc.moveBlockContainerToInventory(NpcBlockContainerSlot(chestPosition, 26), stack.count).status == NpcActionStatus.SUCCEEDED)
            check(chest.getItem(26).isEmpty)
        }
        fun position(x: Int, y: Int, z: Int): NpcBlockPosition {
            val absolute = helper.absolutePos(BlockPos(x, y, z))
            return NpcBlockPosition(absolute.x, absolute.y, absolute.z)
        }
        fun stored(item: Item): Int = (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(item)) chest.getItem(it).count else 0 }
        fun dropped(item: Item): Int {
            val bounds = AABB(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(BlockPos(16, 14, 12)))
            return helper.level.getEntitiesOfClass(ItemEntity::class.java, bounds).sumOf { if (it.item.`is`(item)) it.item.count else 0 }
        }
    }
}
