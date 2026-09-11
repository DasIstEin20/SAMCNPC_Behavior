package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.scores.PlayerTeam
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object TaskCombatLifecycleGameTests {
    private enum class Change { LOST, LEASH, TEAM, PAUSE_PASSIVE, PACK_RELOAD, BODY_RELOAD, COMBAT_DEADLINE, PRIMARY_DEADLINE, CANCEL }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 950, batch = "combat_lifecycle_lost")
    fun lostExactTargetResumesPrimaryWithoutReplacementOrKillCredit(helper: GameTestHelper) = exercise(helper, Change.LOST)
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 950, batch = "combat_lifecycle_leash")
    fun targetOutsideFixedLeashEndsChaseAndResumesPrimary(helper: GameTestHelper) = exercise(helper, Change.LEASH)
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 950, batch = "combat_lifecycle_team")
    fun newFriendlyTeamEndsCombatBeforeFurtherPhysicalDamage(helper: GameTestHelper) = exercise(helper, Change.TEAM)
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 950, batch = "combat_lifecycle_pause")
    fun pausedCombatChangedToPassiveKeepsThePrimaryPausedUntilResume(helper: GameTestHelper) = exercise(helper, Change.PAUSE_PASSIVE)
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 950, batch = "combat_lifecycle_pack_reload")
    fun packReloadReleasesControlsAndPreservesTheOriginalCombatDeadline(helper: GameTestHelper) = exercise(helper, Change.PACK_RELOAD)
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 950, batch = "combat_lifecycle_body_reload")
    fun actualBodyAndTaskReloadPreserveCombatIntentWithoutOldActionHandles(helper: GameTestHelper) = exercise(helper, Change.BODY_RELOAD)
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 950, batch = "combat_lifecycle_deadline")
    fun originalCombatDeadlineResumesPrimaryWithoutKillCredit(helper: GameTestHelper) = exercise(helper, Change.COMBAT_DEADLINE)
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 950, batch = "combat_lifecycle_primary_deadline")
    fun primaryTimeExhaustionDuringCombatStopsBothIntents(helper: GameTestHelper) = exercise(helper, Change.PRIMARY_DEADLINE)
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 950, batch = "combat_lifecycle_cancel")
    fun cancellationDuringCombatLeavesNoReactionOrMovementAfterNewDamage(helper: GameTestHelper) = exercise(helper, Change.CANCEL)

    private fun exercise(helper: GameTestHelper, change: Change) {
        val level = helper.level
        val server = level.server
        val origin = helper.absolutePos(BlockPos.ZERO)
        for (x in -2..50) for (z in -5..7) for (y in 0..5) helper.setBlock(BlockPos(x, y, z), if (y == 0) Blocks.STONE else Blocks.AIR)
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        var body = checkNotNull(type.create(level)) as LivingEntity
        body.moveTo(origin.x + 0.5, origin.y + 1.0, origin.z + 0.5, -90.0F, 0.0F)
        body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
        checkNotNull(body.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
        check(level.addFreshEntity(body))
        val target = checkNotNull(EntityType.COW.create(level))
        target.isNoAi = true
        checkNotNull(target.getAttribute(Attributes.MAX_HEALTH)).baseValue = 200.0
        checkNotNull(target.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
        target.health = target.maxHealth
        target.moveTo(origin.x + 10.5, origin.y + 1.0, origin.z + 4.5)
        check(level.addFreshEntity(target))
        val destination = NpcPosition(origin.x + 48.5, origin.y + 1.0, origin.z + 0.5)
        var taskId: UUID? = null
        var phase = 0
        var age = 0
        var lastBudget = Int.MAX_VALUE
        var interruptedBudget = 0
        var frozenHealth = 0.0F
        var reloading = false
        var done = false
        var team: PlayerTeam? = null
        helper.onEachTick {
            if (done || reloading || !body.onGround() && taskId == null) return@onEachTick
            val service = CoreNpcApi.service(server)
            val npc = checkNotNull(service.find(body.uuid)?.let(service::runtime))
            if (taskId == null) {
                val definition = NavigateTaskDefinition(npc.snapshot().dimensionId, destination,
                    budget = TaskBudget(ticks = if (change == Change.PRIMARY_DEADLINE) 120 else 800))
                check(TaskService.assign(server, npc, definition).status == NpcActionStatus.SUCCEEDED)
                taskId = checkNotNull(TaskStore.forServer(server).get(body.uuid)).id
            }
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            check(record.id == taskId && record.primary.remainingTicks <= lastBudget) { "lifecycle renewed the primary identity/budget" }
            lastBudget = record.primary.remainingTicks
            age++
            if (phase == 0) {
                check(record.frames.size == 1 && target.health == target.maxHealth) { "passive policy or opt-in replayed old damage" }
                if (age == 1) check(body.hurt(body.damageSources().mobAttack(target), 0.25F))
                if (age == 26) check(TaskCombatReactions.configure(server, body.uuid,
                    TaskReactionPolicy(TaskReactionMode.RETALIATE, durationTicks = if (change == Change.PRIMARY_DEADLINE) 400 else 100)).status == NpcActionStatus.SUCCEEDED)
                if (age == 36) {
                    check(body.hurt(body.damageSources().mobAttack(target), 0.25F))
                    phase = 1; age = 0
                }
                return@onEachTick
            }
            if (phase == 1) {
                if (record.active.definition !is AttackTaskDefinition) {
                    check(age < 8) { "registered new-hit reaction did not start" }
                    return@onEachTick
                }
                if (age < 22) return@onEachTick
                check(npc.snapshot().navigation != null || target.health < target.maxHealth) { "lifecycle fixture never exercised physical combat" }
                interruptedBudget = record.primary.remainingTicks
                frozenHealth = target.health
                when (change) {
                    Change.LOST -> target.discard()
                    Change.LEASH -> target.moveTo(origin.x + 49.5, origin.y + 1.0, origin.z + 4.5)
                    Change.TEAM -> {
                        val created = server.scoreboard.addPlayerTeam("cb-${body.id}")
                        created.isAllowFriendlyFire = false
                        server.scoreboard.addPlayerToTeam(body.scoreboardName, created)
                        server.scoreboard.addPlayerToTeam(target.scoreboardName, created)
                        team = created
                    }
                    Change.PAUSE_PASSIVE -> {
                        check(TaskService.pause(server, body.uuid).status == NpcActionStatus.SUCCEEDED)
                        check(TaskCombatReactions.configure(server, body.uuid, TaskReactionPolicy()).status == NpcActionStatus.SUCCEEDED)
                        check(record.status == TaskStatus.PAUSED && record.frames.size == 1)
                        helper.runAfterDelay(30) { check(TaskService.resume(server, body.uuid).status == NpcActionStatus.SUCCEEDED) }
                    }
                    Change.PACK_RELOAD -> {
                        check(BehaviorRuntimeService.reload().accepted)
                        check(npc.snapshot().navigation == null && npc.snapshot().control == null)
                        check(record.primary.remainingTicks == interruptedBudget)
                    }
                    Change.BODY_RELOAD -> {
                        val savedBody = body.saveWithoutId(CompoundTag())
                        val savedTask = TaskStore.forServer(server).save(CompoundTag())
                        val frame = record.active.id
                        val frameTime = record.active.remainingTicks
                        body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK)
                        val stale = npc.stopControl()
                        check(stale.status == NpcActionStatus.REJECTED && stale.code == NpcActionCode.NOT_FOUND)
                        reloading = true
                        helper.runAfterDelay(2) {
                            body = checkNotNull(type.create(level)) as LivingEntity
                            body.load(savedBody)
                            check(level.addFreshEntity(body))
                            val fresh = checkNotNull(service.find(body.uuid)?.let(service::runtime)).snapshot()
                            check(fresh.navigation == null && fresh.control == null && fresh.blockBreak == null)
                            server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(savedTask))
                            val restored = checkNotNull(TaskStore.forServer(server).get(body.uuid))
                            check(restored.id == taskId && restored.active.id == frame && restored.active.remainingTicks == frameTime)
                            check(restored.primary.remainingTicks == interruptedBudget)
                            reloading = false
                        }
                    }
                    Change.CANCEL -> check(TaskService.cancel(server, body.uuid).status == NpcActionStatus.SUCCEEDED)
                    Change.COMBAT_DEADLINE, Change.PRIMARY_DEADLINE -> Unit
                }
                phase = 2; age = 0
                return@onEachTick
            }
            if (phase == 2) {
                if (change == Change.TEAM || change == Change.LEASH || change == Change.PAUSE_PASSIVE) {
                    check(target.health == frozenHealth) { "ended or paused combat continued dealing damage" }
                }
                if (record.status == TaskStatus.PAUSED) {
                    check(record.primary.remainingTicks == interruptedBudget && target.health == frozenHealth)
                    check(npc.snapshot().navigation == null && npc.snapshot().control == null)
                    return@onEachTick
                }
                if (!record.status.terminal) return@onEachTick
                if (change == Change.CANCEL) check(record.status == TaskStatus.CANCELLED && record.reason == TaskReason.USER_CANCELLED)
                else if (change == Change.PRIMARY_DEADLINE) check(record.status == TaskStatus.FAILED && record.reason == TaskReason.TIME_LIMIT)
                else {
                    check(record.status == TaskStatus.COMPLETED && record.reason == TaskReason.ARRIVED) { TaskService.status(server, body.uuid).orEmpty() }
                    check(record.frames.size == 1 && record.completedInterruptions == 1)
                    val reason = when (change) {
                        Change.LOST -> TaskReason.TARGET_UNAVAILABLE
                        Change.LEASH -> TaskReason.LEASH_REACHED
                        Change.TEAM -> TaskReason.PERMISSION_CHANGED
                        Change.PAUSE_PASSIVE -> TaskReason.USER_CANCELLED
                        else -> TaskReason.COMBAT_TIME_LIMIT
                    }
                    check(record.lastCombat?.reason == reason && record.lastCombat?.confirmedKills == 0)
                    check(TaskNavigator.distanceSquared(npc.snapshot().position, destination) <= 1.0)
                }
                check(TaskCodec.read(TaskCodec.write(record)).report() == record.report())
                frozenHealth = target.health
                phase = 3; age = 0
            }
            if (phase == 3) {
                check(target.health == frozenHealth && npc.snapshot().navigation == null && npc.snapshot().control == null && npc.snapshot().blockBreak == null)
                check(BehaviorRuntimeService.assignedPacks(server, body.uuid).isEmpty())
                if (change == Change.CANCEL && age == 12) check(body.hurt(body.damageSources().mobAttack(target), 0.25F))
                if (age >= 25) {
                    done = true
                    body.discard(); target.discard()
                    team?.let(server.scoreboard::removePlayerTeam)
                    helper.succeed()
                }
            }
        }
    }
}
