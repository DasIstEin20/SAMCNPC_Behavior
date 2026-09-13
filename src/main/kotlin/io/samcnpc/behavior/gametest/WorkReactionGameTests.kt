package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object WorkReactionGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1300, batch = "work_protection_reload")
    fun deliveryProtectsSubjectThenResumesAfterPauseAndStoreReload(helper: GameTestHelper) = work(helper, false)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1300, batch = "work_filtered_reaction_reload")
    fun deliveryEngagesOnlyFilteredAreaTargetThenResumesAfterPauseAndStoreReload(helper: GameTestHelper) = work(helper, true)

    private fun work(helper: GameTestHelper, area: Boolean) {
        val server = helper.level.server
        val actor = GameTestActor(helper.level, "ReactionAPI")
        val arena = CombatGameTestArena(helper, actor.player)
        arena.body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
        val subject = arena.mob(EntityType.SHEEP, 3.0, 4.0)
        val attacker = arena.mob(EntityType.COW, 6.0, 3.0)
        val chestPos = helper.absolutePos(BlockPos(12, 1, 0))
        helper.setBlock(BlockPos(12, 1, 0), Blocks.CHEST)
        val destination = NpcBlockPosition(chestPos.x, chestPos.y, chestPos.z)
        var task: UUID? = null; var primary: UUID? = null
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.OAK_LOG, 12))
            arena.assign(npc, DeliveryTaskDefinition(npc.snapshot().dimensionId, destination, "minecraft:oak_log", 8, 4, TaskBudget(ticks = 1200)))
            val record = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
            task = record.id; primary = record.primary.id
        }
        var configured = false; var interrupted = false; var reloaded = false; var resumeAt = -1L; var pausedTicks = -1
        arena.observe { npc, record ->
            val snapshot = npc.snapshot()
            check(record.id == task && record.primary.id == primary) { "reaction replaced the original work identity" }
            if (!configured && TaskNavigator.distanceSquared(snapshot.position, arena.start) >= 1.0) {
                val policy = OperationReactionPolicy(if (area) OperationReactionMode.AREA else OperationReactionMode.PROTECT_UNIT,
                    tactics = OperationCombatTactics(equipArmor = false, useShield = false, heal = false), anchor = arena.start,
                    subjectUuid = if (area) null else subject.uuid,
                    filter = if (area) NpcEntityTypeFilter.of(setOf("minecraft:cow")) else NpcEntityTypeFilter.ANY)
                val reply = OperationSupervisionApi.amend(server, actor.player, npc.npcUuid, OperationAmendmentRequest(
                    record.id, UUID.randomUUID(), record.amendments.revision, snapshot.gameTime, snapshot.gameTime + 1200, OperationChange.Reaction(policy)))
                check(reply.result.status == NpcActionStatus.SUCCEEDED && reply.amendment?.outcome == OperationAmendmentOutcome.APPLIED) { reply.result.detail }
                check(record.amendments.revision == 1)
                if (!area) check(subject.hurt(subject.damageSources().mobAttack(attacker), 1.0F))
                configured = true
            }
            check(subject.health == subject.maxHealth - if (configured && !area) 1.0F else 0.0F) { "reaction attacked its subject or an excluded type" }
            if (record.active.definition is AttackTaskDefinition) {
                interrupted = true
                check((record.active.definition as AttackTaskDefinition).targetUuid == attacker.uuid)
                check(record.reaction.activeFrame == record.active.id)
                if (!reloaded) {
                    check(TaskService.pause(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                    pausedTicks = record.primary.remainingTicks
                    val saved = TaskStore.forServer(server).save(CompoundTag())
                    check(BehaviorRuntimeService.reload().accepted)
                    check(npc.snapshot().navigation == null && npc.snapshot().control == null && npc.snapshot().rangedAttack == null)
                    server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(saved))
                    val restored = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
                    check(restored.status == TaskStatus.PAUSED && restored.primary.remainingTicks == pausedTicks && restored.reaction.activeFrame == record.active.id)
                    if (!area) check(restored.reaction.consumedProtectionHit?.attacker == attacker.uuid)
                    resumeAt = snapshot.gameTime + 4; reloaded = true
                }
            }
            if (resumeAt >= 0) {
                check(record.primary.remainingTicks == pausedTicks) { "paused reaction changed primary deadline" }
                if (snapshot.gameTime >= resumeAt) {
                    check(TaskService.resume(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                    resumeAt = -1
                }
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && record.reason == TaskReason.DELIVERED) { TaskService.status(server, npc.npcUuid).orEmpty() }
                check(configured && interrupted && reloaded && !attacker.isAlive)
                check(record.completedInterruptions == 1 && record.lastCombat?.confirmedKills == 1 && record.reaction.activeFrame == null)
                check(record.primary.resources?.delivered == 8 && record.primary.resources?.retained == 4)
                check(npc.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:oak_log") it.stack.count else 0 } == 4)
                val chest = helper.level.getBlockEntity(chestPos) as ChestBlockEntity
                check((0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.OAK_LOG)) chest.getItem(it).count else 0 } == 8)
                check(record.primary.remainingTicks < pausedTicks && record.primary.remainingTicks > 0)
                actor.close(); arena.succeed(npc, record)
            }
        }
    }
}
