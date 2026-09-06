package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcFacade
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.registries.ForgeRegistries

/** Integration proof that the temporary Behavior policy drives Core only through its public body API. */
@GameTestHolder(SamcnpcBehavior.MOD_ID)
object LumberjackDemoGameTests {
    @JvmStatic
    // A wood-only fallback has to collect genuine drops and dismantle its normal scaffold before
    // the final chest return. Leave deterministic headroom for that complete player-like path.
    @GameTest(template = "empty", timeoutTicks = 7300)
    fun chestGearWoodAndReturnFlow(helper: GameTestHelper) = exerciseFlow(helper)

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 7300, batch = "lumberjack_dirt")
    fun suppliedDirtScaffoldAndReturn(helper: GameTestHelper) = exerciseFlow(helper, Items.DIRT)

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 7300, batch = "lumberjack_cobblestone")
    fun suppliedCobblestoneScaffoldAndReturn(helper: GameTestHelper) = exerciseFlow(helper, Items.COBBLESTONE)

    private fun exerciseFlow(helper: GameTestHelper, scaffoldMaterial: Item? = null) {
        // Keep the chest inside the interaction envelope without placing it in the direct path
        // between the spawned NPC and the distant tree.
        val chestPosition = BlockPos(2, 1, 3)
        // `empty` provides no guaranteed terrain; keep the test path explicitly walkable.
        helper.setBlock(BlockPos(1, 1, 1), Blocks.STONE)
        helper.setBlock(BlockPos(2, 1, 1), Blocks.STONE)
        helper.setBlock(BlockPos(3, 1, 1), Blocks.STONE)
        helper.setBlock(BlockPos(4, 0, 1), Blocks.STONE)
        helper.setBlock(BlockPos(5, 0, 1), Blocks.STONE)
        helper.setBlock(BlockPos(6, 0, 1), Blocks.STONE)
        val chest = configureChest(helper, chestPosition) ?: return
        if (scaffoldMaterial != null) chest.setItem(7, ItemStack(scaffoldMaterial, 12))
        // The tree is outside Core mining reach at spawn. The complete flow must therefore
        // approach a clear stance before it can request a block break.
        // The upper section is beyond both ground and the one-block retained stump. The chest
        // intentionally has no scaffold material: the NPC must fell its retained base only after
        // regular climbing fails, collect the actual wood drops, then use that wood for normal
        // jump/place/land/re-evaluate cycles and normal descent cleanup.
        val trunkPositions = (1..9).map { y -> helper.absolutePos(BlockPos(6, y, 1)) }
        (1..9).forEach { y -> helper.setBlock(BlockPos(6, y, 1), Blocks.OAK_LOG) }
        // This leaf is deliberately on the first eye-to-log ray. The demo must remove it only
        // because Core's bounded raycast says it blocks the supplied trunk log.
        val blockingLeaf = helper.absolutePos(BlockPos(5, 2, 1))
        val persistentOakLeaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(LeavesBlock.PERSISTENT, true)
        helper.setBlock(BlockPos(5, 2, 1), persistentOakLeaves)
        // Snow on foliage occupies its own cell. It must not make the tree look like an
        // unbreakable ceiling: clearing the leaf must also leave the unsupported snow gone.
        val blockingSnow = helper.absolutePos(BlockPos(5, 3, 1))
        helper.setBlock(BlockPos(5, 3, 1), Blocks.SNOW)
        // This nearby leaf is outside every work stance and eye-to-log ray. It must remain: the
        // demo clears only a ray-confirmed access obstruction, never a whole canopy or leaf drops.
        val unrelatedLeaf = helper.absolutePos(BlockPos(10, 3, 1))
        helper.setBlock(BlockPos(10, 3, 1), persistentOakLeaves)
        // This represents a leaf that has already fallen near the work. It starts outside Core's
        // contact pickup envelope, so the Behavior must deliberately route to it once the trunk
        // is safely on the ground instead of relying on passive collision pickup.
        helper.setBlock(BlockPos(4, 0, 2), Blocks.STONE)
        val dropPosition = helper.absolutePos(BlockPos(4, 1, 2))
        val nearbyLeafDrop = ItemEntity(
            helper.level,
            dropPosition.x + 0.5,
            dropPosition.y + 0.2,
            dropPosition.z + 0.5,
            ItemStack(Items.OAK_LEAVES),
        )
        check(helper.level.addFreshEntity(nearbyLeafDrop)) { "Could not add the nearby leaf-drop fixture" }
        // Put terrain in place before adding the Mob: the empty GameTest template itself has no
        // floor, and an entity spawned first can fall before the first behavior tick.
        val npcUuid = spawn(helper)
        val server = checkNotNull(helper.level.server) { "GameTest level has no authoritative server" }
        // Entity-add lifecycle registration is server-tick driven. Poll a bounded number of
        // times so this integration proof exercises the public Core directory without making
        // an assumption about the event's position inside a GameTest tick.
        awaitRegisteredNpc(helper, server, npcUuid) { npc ->
            val started = LumberjackService.start(server, npc, npc.worldView())
            if (started.status != NpcActionStatus.SUCCEEDED) {
                helper.fail("Could not start lumberjack demo: ${started.code}: ${started.detail}")
                return@awaitRegisteredNpc
            }
            var previousTrace = ""
            var traceCount = 0
            val scaffoldPositions = mutableSetOf<io.samcnpc.core.api.NpcBlockPosition>()
            helper.onEachTick {
                val job = LumberjackDemoStore.forServer(server).jobFor(npcUuid)
                job?.pillarSession?.placedPositions?.let(scaffoldPositions::addAll)
                val trace = "phase=${job?.phase} target=${job?.targetPosition} blocked=${job?.blockedLogPosition} stance=${job?.miningStance} rejected=${job?.rejectedMiningStances} recovery=${job?.scaffoldMaterialRecovery} pillar=${job?.pillarSession?.state}"
                if (trace != previousTrace && traceCount < 250) {
                    previousTrace = trace
                    traceCount++
                    com.mojang.logging.LogUtils.getLogger().info("LUMBERJACK TEST tick={} {} position={} wood={}",
                        npc.snapshot().gameTime, trace, npc.snapshot().position,
                        npc.inventoryContents().filter { it.stack.itemId == "minecraft:oak_log" }.sumOf { it.stack.count })
                }
            }
            helper.runAfterDelay(7000) {
                val deposited = (0 until chest.containerSize).any { slot -> chest.getItem(slot).`is`(Items.OAK_LOG) }
                val depositedCount = (0 until chest.containerSize).sumOf { slot ->
                    val stack = chest.getItem(slot)
                    if (stack.`is`(Items.OAK_LOG)) stack.count else 0
                }
                val stillCarried = npc.inventoryContents().any { entry -> entry.stack.itemId == "minecraft:oak_log" }
                val remainingJob = LumberjackDemoStore.forServer(server).jobFor(npc.npcUuid)
                val remainingTrunk = trunkPositions.filter { position -> !helper.level.getBlockState(position).isAir }
                if (remainingTrunk.isNotEmpty()) {
                    val jobBase = remainingJob?.trunkBasePosition
                    val currentPosition = npc.snapshot().position
                    val stumpState = if (jobBase == null) {
                        "base=-"
                    } else {
                        val horizontal = kotlin.math.hypot(currentPosition.x - jobBase.x - 0.5, currentPosition.z - jobBase.z - 0.5)
                        "base=$jobBase sameStance=${remainingJob.miningStance == io.samcnpc.core.api.NpcBlockPosition(jobBase.x, jobBase.y + 1, jobBase.z)} topY=${currentPosition.y >= jobBase.y + 0.95} horizontal=$horizontal"
                    }
                    helper.fail(
                        "Lumberjack demo did not finish the supplied vertical trunk; remaining=$remainingTrunk " +
                            "phase=${remainingJob?.phase} target=${remainingJob?.targetPosition} " +
                            "stance=${remainingJob?.miningStance} jumps=${remainingJob?.climbJumpAttempts} " +
                            "failed=${remainingJob?.failedWorkAttempts} pillar=${remainingJob?.pillarSession} $stumpState position=$currentPosition",
                    )
                } else if (!helper.level.getBlockState(blockingLeaf).isAir) {
                    helper.fail("Lumberjack demo did not clear the leaf that blocked its eye-to-log ray")
                } else if (!helper.level.getBlockState(blockingSnow).isAir) {
                    helper.fail("Lumberjack demo left snow unsupported after clearing its blocking leaf")
                } else if (helper.level.getBlockState(unrelatedLeaf).isAir) {
                    helper.fail("Lumberjack demo removed a nearby leaf that did not block its work ray")
                } else if (nearbyLeafDrop.isAlive) {
                    helper.fail("Lumberjack demo did not actively collect the nearby leaf drop")
                } else if (!deposited || stillCarried) {
                    helper.fail(
                    "Lumberjack demo did not return its collected oak log to the chest; " +
                            "deposited=$deposited carried=$stillCarried phase=${remainingJob?.phase} target=${remainingJob?.targetPosition} " +
                            "pickupTicks=${remainingJob?.pickupTicks} position=${npc.snapshot().position}",
                    )
                } else if (!npc.equipmentContents().head.itemId.equals("minecraft:leather_helmet")) {
                    helper.fail("Lumberjack demo did not equip chest armor")
                } else if (remainingJob != null) {
                    helper.fail("Lumberjack demo did not complete after the bounded scan was exhausted")
                } else if (depositedCount != trunkPositions.size) {
                    helper.fail("Wood was lost or duplicated: chest=$depositedCount expected=${trunkPositions.size}")
                } else if (scaffoldPositions.isEmpty() || scaffoldPositions.any { position ->
                        !helper.level.getBlockState(BlockPos(position.x, position.y, position.z)).isAir
                    }) {
                    helper.fail("The real scaffold was not completely dismantled: $scaffoldPositions")
                } else {
                    helper.succeed()
                }
            }
        }
    }

    private fun configureChest(helper: GameTestHelper, chestPosition: BlockPos): Container? {
        helper.setBlock(chestPosition, Blocks.CHEST)
        val chest = helper.level.getBlockEntity(helper.absolutePos(chestPosition)) as? Container
        if (chest == null) {
            helper.fail("Could not create the chest fixture")
            return null
        }
        chest.setItem(0, ItemStack(Items.LEATHER_HELMET))
        chest.setItem(1, ItemStack(Items.LEATHER_CHESTPLATE))
        chest.setItem(2, ItemStack(Items.LEATHER_LEGGINGS))
        chest.setItem(3, ItemStack(Items.LEATHER_BOOTS))
        chest.setItem(4, ItemStack(Items.IRON_AXE))
        chest.setItem(5, ItemStack(Items.IRON_SHOVEL))
        chest.setItem(6, ItemStack(Items.IRON_PICKAXE))
        chest.setChanged()
        return chest
    }

    private fun spawn(helper: GameTestHelper): java.util.UUID {
        val entityType = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc"))) {
            "Could not resolve the registered SAMCNPC entity type"
        }
        val npc = checkNotNull(entityType.create(helper.level)) { "Could not create SAMCNPC entity" }
        val position = helper.absolutePos(BlockPos(1, 2, 1))
        npc.moveTo(position.x + 0.5, position.y.toDouble(), position.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(npc)) { "Could not add SAMCNPC entity" }
        return npc.uuid
    }

    private fun awaitRegisteredNpc(
        helper: GameTestHelper,
        server: net.minecraft.server.MinecraftServer,
        npcUuid: java.util.UUID,
        attemptsRemaining: Int = MAX_DIRECTORY_REGISTRATION_TICKS,
        onReady: (NpcFacade) -> Unit,
    ) {
        val service = CoreNpcApi.service(server)
        val facade = service.find(npcUuid)?.let(service::runtime)
        if (facade != null) {
            onReady(facade)
            return
        }
        if (attemptsRemaining == 0) {
            helper.fail("Core directory did not register spawned NPC $npcUuid within $MAX_DIRECTORY_REGISTRATION_TICKS server ticks")
            return
        }
        helper.runAfterDelay(1) {
            awaitRegisteredNpc(helper, server, npcUuid, attemptsRemaining - 1, onReady)
        }
    }

    private const val MAX_DIRECTORY_REGISTRATION_TICKS = 20

}
