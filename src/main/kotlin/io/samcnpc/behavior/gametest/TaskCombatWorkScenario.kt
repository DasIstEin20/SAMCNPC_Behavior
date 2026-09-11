package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import java.util.UUID

internal enum class TaskCombatWorkMode { MINING, NAVIGATION, TRANSFER, SCAFFOLD }

/** Real work, accepted damage, selected combat and resumed delivery; shared with the client. */
internal class TaskCombatWorkScenario(private val level: ServerLevel, player: ServerPlayer, private val origin: BlockPos, private val mode: TaskCombatWorkMode = TaskCombatWorkMode.MINING) {
    private val server = level.server
    private val service = CoreNpcApi.service(server)
    val npcUuid: UUID
    private val body: LivingEntity
    private val first: LivingEntity
    private val second: LivingEntity
    private val chest: Container
    var outcome: String? = null
        private set
    var phase = 0
        private set
    private var ticks = 0
    private var age = 0
    private var acceptedHits = 0
    private var combatTicks = 0
    private var lastHit = Long.MIN_VALUE
    private var taskId: UUID? = null
    private var attackFrame: UUID? = null
    private var interruptedBlock: NpcBlockPosition? = null
    private var budgetAtHit = 0
    private var lastBudget = Int.MAX_VALUE
    private var depositedAtHit = 0
    private val treeHeight = if (mode == TaskCombatWorkMode.SCAFFOLD) 9 else 4
    private val expectedDelivery = if (mode == TaskCombatWorkMode.TRANSFER) 96 else treeHeight
    private val wood = WoodSelection(listOf("samcnpc:oak"))

    init {
        for (x in -5..17) for (z in -4..13) for (y in 0..14) level.setBlock(origin.offset(x, y, z),
            (if (y == 0) Blocks.STONE else Blocks.AIR).defaultBlockState(), 3)
        level.setBlock(origin.offset(2, 1, 3), Blocks.CHEST.defaultBlockState(), 3)
        chest = level.getBlockEntity(origin.offset(2, 1, 3)) as Container
        chest.setItem(0, ItemStack(Items.IRON_AXE))
        chest.setItem(1, ItemStack(Items.IRON_SHOVEL))
        chest.setItem(2, ItemStack(Items.DIRT, 12))
        chest.setChanged()
        for (y in 1..treeHeight) level.setBlock(origin.offset(9, y, 4), Blocks.OAK_LOG.defaultBlockState(), 3)
        player.teleportTo(level, origin.x + 2.0, origin.y + 4.0, origin.z + 10.0, -135.0F, 10.0F)
        val summoned = service.summon(NpcSummonRequest(player.uuid, "CombatWork-$mode", level.dimension().location().toString(), position(1.5, 1.0, 3.5), -90.0F))
        check(summoned.result.status == NpcActionStatus.SUCCEEDED)
        npcUuid = checkNotNull(summoned.handle).npcUuid
        body = checkNotNull(level.getEntity(npcUuid)) as LivingEntity
        checkNotNull(body.getAttribute(Attributes.MAX_HEALTH)).baseValue = 200.0
        checkNotNull(body.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
        body.health = body.maxHealth
        if (mode == TaskCombatWorkMode.TRANSFER) {
            body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
            for (count in listOf(64, 32)) {
                val cargo = ItemEntity(level, body.x, body.y, body.z, ItemStack(Items.OAK_LOG, count))
                cargo.setNoPickUpDelay()
                check(level.addFreshEntity(cargo))
            }
        }
        first = attacker(14.5, 9.5)
        second = attacker(16.5, 9.5)
    }

    fun tick() {
        if (outcome != null) return
        check(++ticks < 2400) { "combat/work timeout mode=$mode phase=$phase task=${TaskService.status(server, npcUuid)}" }
        val npc = checkNotNull(service.find(npcUuid)?.let(service::runtime))
        val snapshot = npc.snapshot()
        if (phase == 0) {
            if (!snapshot.onGround) return
            check(BehaviorRuntimeService.assignedPacks(server, npcUuid).isEmpty())
            if (mode == TaskCombatWorkMode.TRANSFER) {
                val cargo = TaskDelivery.inventoryCount(npc, "minecraft:oak_log")
                check(cargo <= 96) { "transfer fixture acquired foreign cargo: $cargo logs" }
                if (cargo != 96) return
            }
            val definition: TaskDefinition = if (mode == TaskCombatWorkMode.TRANSFER) {
                DeliveryTaskDefinition(snapshot.dimensionId, block(2, 1, 3), "minecraft:oak_log", 96, budget = TaskBudget(ticks = 2200))
            } else LumberjackTaskDefinition(snapshot.dimensionId,
                WorkArea(WorkBox(block(0, 1, 0), block(15, 14, 11))), wood, block(2, 1, 3), if (mode == TaskCombatWorkMode.SCAFFOLD) 9 else 3,
                budget = TaskBudget(ticks = 2200))
            check(TaskService.assign(server, npc, definition).status == NpcActionStatus.SUCCEEDED)
            val assigned = checkNotNull(TaskStore.forServer(server).get(npcUuid))
            check(assigned.reaction.policy.mode == TaskReactionMode.PASSIVE)
            taskId = assigned.id
            check(TaskCombatReactions.configure(server, npcUuid, TaskReactionPolicy(TaskReactionMode.RETALIATE)).status == NpcActionStatus.SUCCEEDED)
            advance()
        }
        val record = checkNotNull(TaskStore.forServer(server).get(npcUuid))
        check(record.id == taskId && record.primary.remainingTicks <= lastBudget) { "reaction replaced the primary or renewed its time" }
        lastBudget = record.primary.remainingTicks
        check(record.status != TaskStatus.FAILED && record.status != TaskStatus.CANCELLED) { TaskService.status(server, npcUuid).orEmpty() }
        age++
        when (phase) {
            1 -> {
                val breaking = snapshot.blockBreak
                val ready = when (mode) {
                    TaskCombatWorkMode.MINING -> breaking != null && breaking.progress <= 0.35F
                    TaskCombatWorkMode.NAVIGATION -> snapshot.navigation != null && body.mainHandItem.`is`(Items.IRON_AXE)
                    TaskCombatWorkMode.TRANSFER -> record.primary.resources?.delivered in 1 until 96
                    TaskCombatWorkMode.SCAFFOLD -> !snapshot.onGround && record.primary.lumberjack?.job?.pillarSession?.placedPositions?.isNotEmpty() == true
                }
                if (ready) {
                    interruptedBlock = if (mode == TaskCombatWorkMode.MINING) breaking?.position else null
                    depositedAtHit = deposited()
                    budgetAtHit = record.primary.remainingTicks
                    first.moveTo(body.x, origin.y + 1.0, body.z + 4.0)
                    second.moveTo(body.x + 3.0, origin.y + 1.0, body.z + 4.0)
                    hit(first)
                    advance()
                }
            }
            2 -> {
                val result = record.lastCombat
                if (result != null) {
                    check(result.targetUuid == first.uuid && result.reason == TaskReason.TARGET_DEFEATED && result.confirmedKills == 1) { "unexpected combat result $result" }
                    check(!first.isAlive && second.health == second.maxHealth)
                    check(record.frames.size == 1 && record.completedInterruptions == 1)
                    check(combatTicks >= 30 && acceptedHits >= 4 && record.primary.remainingTicks < budgetAtHit - 20)
                    check(record.reaction.cooldownRemaining > 0)
                    advance()
                } else if (record.active.definition is AttackTaskDefinition) {
                    val definition = record.active.definition as AttackTaskDefinition
                    check(definition.targetUuid == first.uuid && record.frames.size == 2)
                    if (attackFrame == null) attackFrame = record.active.id
                    check(record.active.id == attackFrame && record.completedInterruptions == 0) { "new damage replaced/reset an active combat frame" }
                    check(snapshot.blockBreak == null) { "combat kept the old mining action alive" }
                    val block = interruptedBlock
                    if (block != null) check(level.getBlockState(BlockPos(block.x, block.y, block.z)).`is`(Blocks.OAK_LOG)) { "suspended mining changed the world during combat" }
                    check(deposited() == depositedAtHit) { "suspended work transferred items during combat" }
                    combatTicks++
                    if (level.gameTime - lastHit >= 12L) hit(if (acceptedHits % 2 == 0) first else second)
                } else check(age <= 8) { "registered reaction did not interrupt active mining: ${BehaviorRuntimeService.diagnostic(npcUuid)}" }
            }
            3 -> {
                check(record.frames.size == 1 && record.completedInterruptions == 1 && second.health == second.maxHealth) { "old damage restarted combat instead of work" }
                if (record.status == TaskStatus.COMPLETED) {
                    check(deposited() == expectedDelivery && record.reason == TaskReason.DELIVERED)
                    if (mode == TaskCombatWorkMode.TRANSFER) {
                        val resources = checkNotNull(record.primary.resources)
                        check(resources.delivered == 96 && resources.retained == 0 && resources.validate(96))
                        check(depositedAtHit in 1 until 96) { "fixture did not interrupt between actual transfers" }
                        for (y in 1..treeHeight) check(level.getBlockState(origin.offset(9, y, 4)).`is`(Blocks.OAK_LOG))
                    } else {
                        val state = checkNotNull(record.primary.lumberjack)
                        check(state.resources.delivered(wood) == expectedDelivery && state.resources.entries.values.all { it.valid() })
                        for (y in 1..treeHeight) check(level.getBlockState(origin.offset(9, y, 4)).isAir)
                        check(state.job.pillarSession == null) { "completed wood task retained a support session" }
                    }
                    check(TaskCodec.read(TaskCodec.write(record)).report() == record.report())
                    check(UnresolvedWorkStore.forServer(server).blocksFor(npcUuid).isEmpty())
                    advance()
                }
            }
            4 -> {
                check(snapshot.navigation == null && snapshot.control == null && snapshot.blockBreak == null && snapshot.itemUse == null)
                check(second.health == second.maxHealth && record.completedInterruptions == 1)
                check(BehaviorRuntimeService.assignedPacks(server, npcUuid).isEmpty())
                if (age >= 35) outcome = "Task combat $mode: actual work interrupted -> both sources cause accepted hits -> stable finite target physically defeated -> original task/budget retained -> fresh observation -> actual delivery=$expectedDelivery -> controls released; acceptedHits=$acceptedHits combatTicks=$combatTicks ticks=$ticks"
            }
        }
    }

    fun close() { body.discard(); first.discard(); second.discard() }
    private fun hit(attacker: LivingEntity) {
        check(body.hurt(body.damageSources().mobAttack(attacker), 0.25F)) { "combat/work fixture hit was rejected" }
        lastHit = level.gameTime
        acceptedHits++
    }
    private fun deposited(): Int = (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.OAK_LOG)) chest.getItem(it).count else 0 }
    private fun attacker(x: Double, z: Double): LivingEntity {
        val entity = checkNotNull(EntityType.COW.create(level))
        entity.isNoAi = true
        checkNotNull(entity.getAttribute(Attributes.MAX_HEALTH)).baseValue = if (mode == TaskCombatWorkMode.SCAFFOLD) 12.0 else 60.0
        checkNotNull(entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
        entity.health = entity.maxHealth
        entity.moveTo(origin.x + x, origin.y + 1.0, origin.z + z)
        check(level.addFreshEntity(entity))
        return entity
    }
    private fun advance() { phase++; age = 0 }
    private fun position(x: Double, y: Double, z: Double) = NpcPosition(origin.x + x, origin.y + y, origin.z + z)
    private fun block(x: Int, y: Int, z: Int) = NpcBlockPosition(origin.x + x, origin.y + y, origin.z + z)
}
