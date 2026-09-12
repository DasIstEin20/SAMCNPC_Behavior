package io.samcnpc.behavior.operations

import io.samcnpc.behavior.kernel.work.HarvestWorkClaims
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.FarmBlock
import net.minecraft.world.phys.AABB
import java.util.Random
import java.util.UUID

internal enum class ZooWorldCase(val operation: OperationKind, val combat: Boolean) {
    MINER_TARGET_REMOVED(OperationKind.MINING, false),
    FARMER_MATURE_BECOMES_YOUNG(OperationKind.FARM, false),
    MINER_COMBAT_RESUME(OperationKind.MINING, true),
    FARMER_COMBAT_RESUME(OperationKind.FARM, true),
    LUMBERJACK_COMBAT_RESUME(OperationKind.WOOD, true),
    COURIER_COMBAT_RESUME(OperationKind.TRANSPORT, true),
}

internal class ZooWorldScenario(server: MinecraftServer, val kind: ZooWorldCase, seed: Long) : ZooScenario {
    override val scene = OperationScene.create(server.overworld(), kind.operation, BlockPos(3100 + kind.ordinal * 64, 80, 1000))
    private val offset = Random(seed xor kind.ordinal.toLong()).nextInt(2) + 3
    private val timeline = ZooIncidents(seed, listOf(ZooIncident(kind.name, "observed_before_effect",
        mapOf("operation" to kind.operation.name, "attackerOffsetZ" to offset.toString()))))
    private var oracle: ZooTaskOracle? = null
    private var ticks = 0
    private var ready = false
    private var quietTicks = 0
    private var originalPool = 0L
    private var externalDelta = 0L
    private var changedAt: BlockPos? = null
    private var attacker: LivingEntity? = null
    private var combatTicks = 0
    private var combatFrame: UUID? = null
    private var frozenWork: Int? = null
    private var lastEvidence: Map<String, Any?> = emptyMap()
    override var complete = false
        private set

    override fun tick() {
        if (complete) return
        check(++ticks < 6200) { "$kind timed out: ${scene.record.report()}" }
        if (!ready) {
            if (!scene.loaded || !scene.body.onGround()) return
            prepare()
            oracle = ZooTaskOracle(sample())
            originalPool = resourcePool()
            ready = true
        }
        val record = scene.record
        check(record.status != TaskStatus.FAILED && record.status != TaskStatus.CANCELLED) { "$kind ${record.report()}" }
        checkNotNull(oracle).observe(sample())
        if (kind == ZooWorldCase.FARMER_COMBAT_RESUME) {
            val matureBefore = matureCrops()
            OperationResourceCases.advanceWorldInput(scene)
            externalDelta += matureCrops() - matureBefore
        }
        val incident = timeline.pending(if (incidentReady()) "observed_before_effect" else "waiting")
        if (incident != null) {
            val before = facts()
            applyIncident()
            timeline.record(incident, ticks, before, facts())
        }
        if (record.active.definition is AttackTaskDefinition) {
            val frame = record.active
            if (combatFrame == null) { combatFrame = frame.id; frozenWork = completedWork() }
            check(frame.id == combatFrame && record.frames.size == 2)
            check((frame.definition as AttackTaskDefinition).targetUuid == checkNotNull(attacker).uuid)
            check(scene.npc.snapshot().blockBreak == null) { "$kind retained the interrupted block strike" }
            check(completedWork() == frozenWork) { "$kind progressed primary world effects during combat" }
            combatTicks++
        }
        lastEvidence = capture()
        if (!record.status.terminal) return
        check(timeline.complete) { "$kind finished without exercising its incident" }
        verifyPhysicalResult()
        if (++quietTicks < 40) return
        checkNotNull(oracle).observe(sample(), released = true)
        val outsider = UUID(0, 1)
        val snapshot = scene.npc.snapshot()
        val target = NpcPosition(scene.origin.x + 12.5, scene.origin.y + 1.0, scene.origin.z + 0.5)
        check(HarvestWorkClaims.kernel.incidentalCollectionFilter(outsider, outsider, snapshot.dimensionId, target, 2.0, snapshot.gameTime)(target)) {
            "$kind leaked a harvest reservation"
        }
        complete = true
        lastEvidence = capture()
        scene.close()
        check(CoreNpcApi.service(scene.server).find(scene.npcId)?.let(CoreNpcApi.service(scene.server)::runtime) == null)
    }

    private fun prepare() {
        if (kind.combat) scene.give(Items.IRON_SWORD)
        val dimension = scene.npc.snapshot().dimensionId
        when (kind) {
            ZooWorldCase.MINER_TARGET_REMOVED -> {
                scene.makeChest(0, 7); scene.give(Items.IRON_PICKAXE); scene.give(Items.RAW_IRON, 5)
                for (x in 12..14) scene.level.setBlock(scene.pos(x, 1, 0), Blocks.IRON_ORE.defaultBlockState(), 3)
                scene.assign(MiningTaskDefinition(dimension,
                    MiningWorkOrder(scene.area(12, 14), MiningMethod.EXPOSED, WorkResourceIds(listOf("minecraft:iron_ore"))),
                    WorkResourceIds(listOf("minecraft:raw_iron")), scene.choices(0, 7), 2, MiningCounting.DELIVERED_ITEMS,
                    scene.start, returnTo = scene.start, budget = TaskBudget(6000)))
            }
            ZooWorldCase.FARMER_MATURE_BECOMES_YOUNG -> {
                scene.makeChest(0, 7); scene.give(Items.IRON_HOE); scene.give(Items.WHEAT, 5); scene.give(Items.WHEAT_SEEDS, 5)
                for (x in 12..14) {
                    scene.level.setBlock(scene.pos(x, 0, 0), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), 3)
                    scene.level.setBlock(scene.pos(x, 1, 0), (Blocks.WHEAT as CropBlock).getStateForAge(7), 3)
                }
                scene.assign(FarmTaskDefinition(dimension, FarmWorkOrder(scene.area(12, 14), FarmCrop.WHEAT, FarmMode.HARVEST),
                    scene.choices(0, 7), 2, scene.start, returnTo = scene.start, budget = TaskBudget(6000)))
            }
            else -> OperationCases.prepare(scene)
        }
        if (kind.combat) check(TaskCombatReactions.configure(scene.server, scene.npcId,
            TaskReactionPolicy(TaskReactionMode.RETALIATE)).status == NpcActionStatus.SUCCEEDED)
    }

    private fun incidentReady(): Boolean {
        if (timeline.complete) return false
        val record = scene.record
        val snapshot = scene.npc.snapshot()
        return when (kind) {
            ZooWorldCase.MINER_TARGET_REMOVED -> record.primary.mining?.phase == MiningPhase.WORK && snapshot.blockBreak == null
            ZooWorldCase.FARMER_MATURE_BECOMES_YOUNG, ZooWorldCase.FARMER_COMBAT_RESUME -> record.primary.farming?.phase == FarmPhase.HARVEST && snapshot.blockBreak == null
            ZooWorldCase.MINER_COMBAT_RESUME, ZooWorldCase.LUMBERJACK_COMBAT_RESUME -> snapshot.blockBreak?.let { it.progress < 0.35F } == true
            ZooWorldCase.COURIER_COMBAT_RESUME -> record.primary.transport?.ledger?.withdrawn == 12 && record.primary.transport?.ledger?.delivered == 0
        }
    }

    private fun applyIncident() {
        when (kind) {
            ZooWorldCase.MINER_TARGET_REMOVED -> {
                val target = checkNotNull(scene.record.primary.mining?.target).position
                changedAt = BlockPos(target.x, target.y, target.z)
                check(scene.level.getBlockState(checkNotNull(changedAt)).`is`(Blocks.IRON_ORE))
                scene.level.setBlock(checkNotNull(changedAt), Blocks.AIR.defaultBlockState(), 3)
                externalDelta--
            }
            ZooWorldCase.FARMER_MATURE_BECOMES_YOUNG -> {
                val target = checkNotNull(scene.record.primary.farming?.target)
                changedAt = BlockPos(target.x, target.y, target.z)
                scene.level.setBlock(checkNotNull(changedAt), (Blocks.WHEAT as CropBlock).getStateForAge(0), 3)
                externalDelta--
            }
            else -> {
                val enemy = scene.enemy(EntityType.COW, 0, 0)
                enemy.moveTo(scene.body.x, scene.origin.y + 1.0, scene.body.z + offset)
                attacker = enemy
                check(scene.body.hurt(scene.body.damageSources().mobAttack(enemy), 0.25F)) { "native damage input rejected" }
            }
        }
    }

    private fun verifyPhysicalResult() {
        if (kind == ZooWorldCase.MINER_TARGET_REMOVED) {
            scene.requireCompleted(); scene.requireReturned()
            val state = checkNotNull(scene.record.primary.mining)
            check(state.selection.removed.size == 2 && state.resources.physical.entries.values.all { it.valid() })
            val removed = checkNotNull(changedAt)
            check(NpcBlockPosition(removed.x, removed.y, removed.z) !in state.selection.removed)
            check((12..14).all { scene.level.getBlockState(scene.pos(it, 1, 0)).isAir })
            check(scene.count(0, 7, Items.RAW_IRON) == 2 && scene.carried("minecraft:raw_iron") == 5)
        } else if (kind == ZooWorldCase.FARMER_MATURE_BECOMES_YOUNG) {
            scene.requireCompleted(); scene.requireReturned()
            val young = checkNotNull(changedAt)
            val state = checkNotNull(scene.record.primary.farming)
            check(state.totalHarvests() == 2 && NpcBlockPosition(young.x, young.y, young.z) !in state.harvested)
            check(scene.level.getBlockState(young) == (Blocks.WHEAT as CropBlock).getStateForAge(0))
            check(scene.count(0, 7, Items.WHEAT) == 2 && scene.carried("minecraft:wheat") == 5)
            check(state.resources.physical.entries.values.all { it.valid() })
        } else {
            OperationCases.verify(scene)
            val combat = checkNotNull(scene.record.lastCombat)
            check(combatTicks > 0 && combat.reason == TaskReason.TARGET_DEFEATED && combat.confirmedKills == 1)
            check(!checkNotNull(attacker).isAlive && scene.record.completedInterruptions == 1 && scene.record.frames.size == 1)
        }
        ZooResourceOracle.conserve(originalPool, resourcePool(), externalDelta)
    }

    private fun sample(): ZooTaskSample {
        val snapshot = scene.npc.snapshot()
        val point = snapshot.position
        val record = scene.record
        val controls = listOf(snapshot.navigation, snapshot.control, snapshot.blockBreak, snapshot.itemUse, snapshot.rangedAttack).count { it != null }
        return ZooTaskSample(record.id, record.primary.id, record.primary.remainingTicks, record.frames.size,
            point.x >= scene.origin.x - 3 && point.x <= scene.origin.x + 33 && point.z >= scene.origin.z - 8 &&
                point.z <= scene.origin.z + 13 && point.y >= scene.origin.y && point.y <= scene.origin.y + 11, controls)
    }
    private fun completedWork(): Int = when (kind.operation) {
        OperationKind.MINING -> scene.record.primary.mining?.selection?.removed?.size ?: 0
        OperationKind.FARM -> scene.record.primary.farming?.totalHarvests() ?: 0
        OperationKind.WOOD -> scene.record.primary.lumberjack?.observedRemovedBlocks?.size ?: 0
        OperationKind.TRANSPORT -> scene.record.primary.transport?.ledger?.delivered ?: 0
        else -> error("unsupported Zoo resource family")
    }
    private fun matureCrops(): Int = (12..14).count { scene.level.getBlockState(scene.pos(it, 1, 0)) == (Blocks.WHEAT as CropBlock).getStateForAge(7) }
    private fun resourcePool(): Long {
        val item: Item
        val itemId: String
        val stored: Int
        val unharvested: Int
        when (kind.operation) {
            OperationKind.MINING -> {
                item = Items.RAW_IRON; itemId = "minecraft:raw_iron"; stored = scene.count(0, 7, item)
                unharvested = (12..14).count { scene.level.getBlockState(scene.pos(it, 1, 0)).`is`(Blocks.IRON_ORE) }
            }
            OperationKind.FARM -> { item = Items.WHEAT; itemId = "minecraft:wheat"; stored = scene.count(0, 7, item); unharvested = matureCrops() }
            OperationKind.WOOD -> {
                item = Items.OAK_LOG; itemId = "minecraft:oak_log"; stored = scene.count(0, 7, item)
                unharvested = (1..3).count { scene.level.getBlockState(scene.pos(12, it, 0)).`is`(Blocks.OAK_LOG) }
            }
            OperationKind.TRANSPORT -> {
                item = Items.DIAMOND; itemId = "minecraft:diamond"; stored = scene.count(10, 3, item) + scene.count(24, -3, item); unharvested = 0
            }
            else -> error("unsupported resource balance")
        }
        val drops = scene.level.getEntitiesOfClass(ItemEntity::class.java, AABB(scene.pos(-3, 0, -8), scene.pos(33, 12, 13)))
            .sumOf { if (it.item.`is`(item)) it.item.count else 0 }
        return scene.carried(itemId).toLong() + stored + unharvested + drops
    }
    private fun facts() = mapOf("taskId" to scene.record.id.toString(), "primaryId" to scene.record.primary.id.toString(),
        "remainingTicks" to scene.record.primary.remainingTicks.toString(), "completedWork" to completedWork().toString(),
        "resourcePool" to resourcePool().toString(), "changedAt" to changedAt.toString(), "position" to scene.npc.snapshot().position.toString())
    private fun capture(): Map<String, Any?> = linkedMapOf("case" to kind.name, "status" to if (complete) "PASS" else "RUNNING",
        "worldSeed" to scene.level.seed, "inputSeed" to timeline.seed, "ticks" to ticks, "npc" to scene.npcId.toString(),
        "origin" to scene.origin.toShortString(), "incidents" to timeline.evidence, "task" to scene.record.report(),
        "combatTicks" to combatTicks, "oracleObservations" to oracle?.observations, "originalResourcePool" to originalPool,
        "externalResourceDelta" to externalDelta, "currentResourcePool" to resourcePool(), "facts" to facts())
    override fun evidence(): Map<String, Any?> = lastEvidence.ifEmpty { mapOf("case" to kind.name, "status" to "SETTING_UP") }
}
