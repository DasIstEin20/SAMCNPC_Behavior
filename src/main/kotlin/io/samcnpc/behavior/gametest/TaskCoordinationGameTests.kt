package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.*

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object TaskCoordinationGameTests {
    private val logger = com.mojang.logging.LogUtils.getLogger()
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2300, batch = "shared_cargo_reservations")
    fun twoTransportsShareRealSourceAndRecipientWhilePauseAndExternalRemovalPreserveCounts(helper: GameTestHelper) {
        val server = helper.level.server; val arena = CombatGameTestArena(helper)
        val otherBody = extra(helper, arena, 1.5)
        val source = chest(helper, 2, 0); val destination = chest(helper, 16, 0)
        source.setItem(0, ItemStack(Items.OAK_LOG, 64))
        fun definition(npc: NpcFacade) = TransportTaskDefinition(npc.snapshot().dimensionId, ContainerChoices(listOf(pos(source))), ContainerChoices(listOf(pos(destination))),
            "minecraft:oak_log", 20, arena.start, budget = TaskBudget(ticks = 2200))
        arena.onReady { npc -> arena.assign(npc, definition(npc)) }
        var otherAssigned = false; var paused = false; var resumed = false; var external = 0; var pausedBudget = 0; var resumeAt = 0L
        arena.observe { npc, record ->
            val service = CoreNpcApi.service(server)
            val other = checkNotNull(service.find(otherBody.uuid)?.let(service::runtime))
            if (!otherAssigned && other.snapshot().onGround) {
                check(TaskService.assign(server, other, definition(other)).status == NpcActionStatus.SUCCEEDED); otherAssigned = true
            }
            val otherTask = TaskStore.forServer(server).get(otherBody.uuid)
            val ledger = checkNotNull(record.primary.transport).ledger
            if (!paused && ledger.withdrawn == 20 && !record.status.terminal) {
                check(TaskService.pause(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                paused = true; pausedBudget = record.primary.remainingTicks; resumeAt = npc.snapshot().gameTime + 30
                external = source.removeItem(0, 4).count; source.setChanged(); check(external == 4)
            }
            if (paused && !resumed) {
                check(record.primary.remainingTicks == pausedBudget)
                if (npc.snapshot().gameTime >= resumeAt) { check(TaskService.resume(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED); resumed = true }
            }
            if (record.status.terminal && otherTask?.status?.terminal == true) {
                check(record.status == TaskStatus.COMPLETED && otherTask.status == TaskStatus.COMPLETED) {
                    "first=${TaskService.status(server, npc.npcUuid)} second=${TaskService.status(server, other.npcUuid)}"
                }
                check(paused && resumed && external == 4)
                check(ledger.withdrawn == 20 && ledger.delivered == 20 && otherTask.primary.transport?.ledger?.withdrawn == 20 && otherTask.primary.transport?.ledger?.delivered == 20)
                check(record.totalFailures == 0 && otherTask.totalFailures == 0) { "reservation waits consumed retry attempts" }
                check(count(source, Items.OAK_LOG) == 20 && count(destination, Items.OAK_LOG) == 40)
                check(TaskDelivery.inventoryCount(npc, "minecraft:oak_log") == 0 && TaskDelivery.inventoryCount(other, "minecraft:oak_log") == 0)
                check(BehaviorRuntimeService.assignedPacks(server, other.npcUuid).isEmpty())
                check(other.snapshot().navigation == null && other.snapshot().control == null)
                otherBody.discard(); arena.succeed(npc, record)
            }
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2700, batch = "three_fair_wood_workers")
    fun threeWoodWorkersAcquireDistinctNearbyTreesAndShareSuppliesAndOutput(helper: GameTestHelper) = runWoodRound(helper,1)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2700, batch = "three_fair_wood_round_2")
    fun threeWoodWorkersRound2(helper: GameTestHelper) = runWoodRound(helper,2)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2700, batch = "three_fair_wood_round_3")
    fun threeWoodWorkersRound3(helper: GameTestHelper) = runWoodRound(helper,3)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2700, batch = "three_fair_wood_round_4")
    fun threeWoodWorkersRound4(helper: GameTestHelper) = runWoodRound(helper,4)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2700, batch = "three_fair_wood_round_5")
    fun threeWoodWorkersRound5(helper: GameTestHelper) = runWoodRound(helper,5)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2700, batch = "three_fair_wood_round_6")
    fun threeWoodWorkersRound6(helper: GameTestHelper) = runWoodRound(helper,6)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2700, batch = "three_fair_wood_round_7")
    fun threeWoodWorkersRound7(helper: GameTestHelper) = runWoodRound(helper,7)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2700, batch = "three_fair_wood_round_8")
    fun threeWoodWorkersRound8(helper: GameTestHelper) = runWoodRound(helper,8)

    // Each round has its own tracked batch. Vanilla annotation retries can escape the original
    // tracker, while registering a fresh arena inside its scheduled callback mutates its iterator.
    private fun runWoodRound(helper: GameTestHelper, round: Int) {
        logger.info("WOOD_ROUND_START round={}/8",round)
        val server = helper.level.server; val arena = CombatGameTestArena(helper)
        val otherBodies = listOf(extra(helper, arena, -2.0), extra(helper, arena, 2.0))
        val source = chest(helper, 2, 3); val output = chest(helper, 18, 0)
        for (slot in 0..2) source.setItem(slot, ItemStack(Items.IRON_AXE))
        for (z in listOf(-6, 0, 6)) for (y in 1..3) helper.setBlock(BlockPos(9, y, z), Blocks.OAK_LOG)
        val wood = WoodSelection(listOf("samcnpc:oak"))
        fun definition(npc: NpcFacade) = LumberjackTaskDefinition(npc.snapshot().dimensionId,
            WorkArea(WorkBox(absolute(helper, 7, 1, -8), absolute(helper, 11, 5, 8))), wood, pos(output), 3,
            budget = TaskBudget(ticks = 2600), version = 2, supplySources = ContainerChoices(listOf(pos(source))))
        arena.onReady { npc -> arena.assign(npc, definition(npc)) }
        val assigned = mutableSetOf<java.util.UUID>()
        val observedStates = mutableMapOf<java.util.UUID, String>()
        arena.observe { npc, record ->
            val service = CoreNpcApi.service(server)
            val others = otherBodies.map { checkNotNull(service.find(it.uuid)?.let(service::runtime)) }
            for (other in others) if (other.npcUuid !in assigned && other.snapshot().onGround) {
                check(TaskService.assign(server, other, definition(other)).status == NpcActionStatus.SUCCEEDED); assigned.add(other.npcUuid)
            }
            val records = listOf(record) + others.mapNotNull { TaskStore.forServer(server).get(it.npcUuid) }
            for (part in records) {
                val woodState = checkNotNull(part.primary.lumberjack)
                val row = woodState.resources.entries["minecraft:oak_log"]
                val key = "${woodState.job.phase}:${row?.gathered}:${row?.retained}:${row?.delivered}:${woodState.job.trunkBasePosition}"
                if (observedStates.put(part.npcUuid, key) != key) {
                    val facade = (listOf(npc) + others).first { it.npcUuid == part.npcUuid }
                    logger.info("WOOD_GROUP npc={} state={} position={} pickupPhase={} target={}",
                        part.npcUuid,key,facade.snapshot().position,woodState.job.chestAccessStage,woodState.job.chestAccessTarget)
                }
            }
            val activeTrees = records.filter { !it.status.terminal }.mapNotNull { it.primary.lumberjack?.job?.trunkBasePosition }
            check(activeTrees.distinct().size == activeTrees.size) { "two workers planned the same trunk" }
            if (records.size == 3 && records.all { it.status.terminal }) {
                check(records.all { it.status == TaskStatus.COMPLETED }) { (listOf(npc) + others).joinToString("\n") { TaskService.status(server, it.npcUuid).orEmpty() } }
                check(records.all { it.primary.lumberjack?.resources?.delivered(wood) == 3 })
                check(count(output, Items.OAK_LOG) == 9 && count(source, Items.OAK_LOG) == 0 && count(source, Items.IRON_AXE) == 0)
                check(records.all { it.primary.lumberjack?.resources?.entries?.values?.all { row -> row.valid() } == true })
                for (z in listOf(-6, 0, 6)) for (y in 1..3) check(helper.getBlockState(BlockPos(9, y, z)).isAir)
                for (other in others) check(BehaviorRuntimeService.assignedPacks(server, other.npcUuid).isEmpty())
                logger.info("WOOD_ROUND_COMPLETE round={}/8",round)
                otherBodies.forEach { it.discard() }; arena.succeed(npc, record)
            }
        }
    }
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1700, batch = "harvest_contact_permission")
    fun idleNeighborCannotCollectInsideLiveHarvestClaimAndContactPickupResumesWhenReleased(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper); val server = helper.level.server
        val neighborBody = extra(helper, arena, -4.0)
        val output = chest(helper,18,0)
        for (y in 1..3) helper.setBlock(BlockPos(9,y,0),Blocks.OAK_LOG)
        val probePosition = NpcPosition(arena.start.x + 9.0, arena.start.y, arena.start.z - 4.0)
        var probe: net.minecraft.world.entity.item.ItemEntity? = null
        var deniedTicks = 0; var releasedAt: Long? = null; var collected = false
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_AXE))
            arena.assign(npc,LumberjackTaskDefinition(npc.snapshot().dimensionId,
                WorkArea(WorkBox(absolute(helper,8,1,-1),absolute(helper,10,4,1))),WoodSelection(listOf("samcnpc:oak")),
                pos(output),3,budget=TaskBudget(ticks=1600),version=2))
        }
        arena.observe { npc, record ->
            val service = CoreNpcApi.service(server)
            val neighbor = checkNotNull(service.find(neighborBody.uuid)?.let(service::runtime))
            val permitted = io.samcnpc.behavior.lumberjack.LumberjackService.incidentalDropFilter(neighbor.snapshot(),neighbor.npcUuid,16.0)(probePosition)
            if (probe == null && !permitted) {
                neighborBody.moveTo(probePosition.x,probePosition.y,probePosition.z,0.0F,0.0F)
                val item = net.minecraft.world.entity.item.ItemEntity(helper.level,probePosition.x,probePosition.y,probePosition.z,ItemStack(Items.IRON_NUGGET,4))
                item.setNoPickUpDelay(); item.deltaMovement = net.minecraft.world.phys.Vec3.ZERO; item.setNoGravity(true)
                check(helper.level.addFreshEntity(item)); probe = item
                check(neighbor.pickupItem(item.uuid).code == NpcActionCode.PERMISSION_DENIED) { "explicit pickup bypassed harvest reservation" }
            }
            val item = probe
            if (item != null) {
                if (!permitted) {
                    deniedTicks++
                    check(item.isAlive && item.item.count == 4 && TaskDelivery.inventoryCount(neighbor,"minecraft:iron_nugget") == 0) {
                        "native contact pickup bypassed harvest reservation after $deniedTicks ticks"
                    }
                } else {
                    if (releasedAt == null) releasedAt = npc.snapshot().gameTime
                    collected = item.isRemoved && TaskDelivery.inventoryCount(neighbor,"minecraft:iron_nugget") == 4
                    check(collected || npc.snapshot().gameTime - checkNotNull(releasedAt) <= 15) { "native pickup did not resume after claim release" }
                }
            }
            if (record.status.terminal && collected) {
                check(record.status == TaskStatus.COMPLETED && deniedTicks >= 12) { TaskService.status(server,npc.npcUuid).orEmpty() }
                check(count(output,Items.OAK_LOG) == 3 && record.primary.lumberjack?.resources?.delivered(WoodSelection(listOf("samcnpc:oak"))) == 3)
                for (y in 1..3) check(helper.getBlockState(BlockPos(9,y,0)).isAir)
                neighborBody.discard(); arena.succeed(npc,record)
            }
        }
    }

    private fun extra(helper: GameTestHelper, arena: CombatGameTestArena, z: Double): LivingEntity {
        val body = checkNotNull(arena.body.type.create(helper.level)) as LivingEntity
        body.moveTo(arena.start.x, arena.start.y, arena.start.z + z, -90.0F, 0.0F)
        check(helper.level.addFreshEntity(body)); return body
    }
    private fun chest(helper: GameTestHelper, x: Int, z: Int): ChestBlockEntity {
        helper.setBlock(BlockPos(x, 1, z), Blocks.CHEST)
        return helper.level.getBlockEntity(helper.absolutePos(BlockPos(x, 1, z))) as ChestBlockEntity
    }
    private fun pos(chest: ChestBlockEntity) = NpcBlockPosition(chest.blockPos.x, chest.blockPos.y, chest.blockPos.z)
    private fun count(chest: ChestBlockEntity, item: Item) = (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(item)) chest.getItem(it).count else 0 }
    private fun absolute(helper: GameTestHelper, x: Int, y: Int, z: Int): NpcBlockPosition {
        val p = helper.absolutePos(BlockPos(x, y, z)); return NpcBlockPosition(p.x, p.y, p.z)
    }
}
