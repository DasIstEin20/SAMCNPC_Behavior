package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.combat.*
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionUtils
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object CombatMissionGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 650, batch = "mission_ranged_equipment")
    fun carriedBowArmorAndRealArrowsMeetOnlyThePermittedKillQuota(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper)
        arena.body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.WOODEN_SWORD))
        val target = arena.mob(EntityType.COW, 8.0, 0.0)
        target.health = target.maxHealth
        val untouched = arena.mob(EntityType.SHEEP, 5.0, 4.0)
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.BOW)); arena.give(npc, ItemStack(Items.ARROW, 8)); arena.give(npc, ItemStack(Items.IRON_HELMET))
            check(npc.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:arrow") it.stack.count else 0 } == 8)
            val tactics = CombatTactics(preference = CombatWeaponPreference.RANGED, allowed = CombatWeaponAllowance.RANGED, useShield = false)
            arena.assign(npc, AreaAttackTaskDefinition(npc.snapshot().dimensionId, arena.start, 24.0,
                NpcEntityTypeFilter.of(setOf("minecraft:cow")), 1, tactics = tactics, budget = TaskBudget(ticks = 600)))
        }
        var charged = false; var equipped = false
        arena.observe { npc, record ->
            charged = charged || npc.snapshot().rangedAttack != null
            equipped = equipped || arena.body.getItemBySlot(EquipmentSlot.HEAD).`is`(Items.IRON_HELMET)
            check(untouched.health == untouched.maxHealth) { "filtered area attack damaged an excluded sheep" }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && record.reason == TaskReason.AREA_CLEARED) { TaskService.status(helper.level.server, npc.npcUuid).orEmpty() }
                check(charged && equipped && !target.isAlive) { "real ranged charge/equipment/defeat not observed" }
                check(record.primary.combat?.defeatedTargets == setOf(target.uuid))
                val arrows = npc.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:arrow") it.stack.count else 0 }
                check(arrows in 0..7) { "real ranged use did not consume carried arrows: $arrows" }
                check(npc.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:bow") it.stack.count else 0 } == 1)
                check(TaskNavigator.distanceSquared(npc.snapshot().position, arena.start) <= 1.0)
                arena.succeed(npc, record)
            }
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 650, batch = "mission_real_healing")
    fun actualRestorativePotionIsConsumedBeforeCarriedSwordCombatResumes(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper)
        arena.body.setItemSlot(EquipmentSlot.MAINHAND, PotionUtils.setPotion(ItemStack(Items.POTION), Potions.STRONG_HEALING))
        arena.body.health = 6.0F
        val target = arena.mob(EntityType.COW, 6.0, 0.0, 200.0)
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.IRON_SWORD))
            check(npc.snapshot().equipment.mainHand.combat.healingConsumable)
            check(npc.snapshot().healthFraction == 0.3) { "6/20 health must reach the configured 30% threshold: ${npc.snapshot().healthFraction}" }
            arena.assign(npc, AttackTaskDefinition(npc.snapshot().dimensionId, target.uuid, arena.start, tactics = CombatTactics()))
        }
        var drinking = false
        arena.observe { npc, record ->
            drinking = drinking || npc.snapshot().itemUse?.itemId == "minecraft:potion"
            check(!record.status.terminal) { "healing task terminated before effect and physical counterattack: ${TaskService.status(helper.level.server, npc.npcUuid)}" }
            if (arena.body.health >= 14.0F && target.health < target.maxHealth) {
                val state = checkNotNull(record.primary.combat)
                check(drinking && state.observedHealing && state.healingUses == 1 && !state.retreating)
                val inventory = npc.inventoryContents()
                check(inventory.none { it.stack.itemId == "minecraft:potion" })
                check(inventory.sumOf { if (it.stack.itemId == "minecraft:glass_bottle") it.stack.count else 0 } == 1)
                check(inventory.sumOf { if (it.stack.itemId == "minecraft:iron_sword") it.stack.count else 0 } == 1)
                check(TaskService.cancel(helper.level.server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                arena.succeed(npc, record)
            }
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 650, batch = "mission_defend_subject")
    fun actualAcceptedAttackerOfProtectedSubjectIsDefeatedThenDefenderReturns(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper)
        arena.body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
        val subject = arena.mob(EntityType.COW, 3.0, 3.0)
        val attacker = arena.mob(EntityType.COW, 6.0, 3.0)
        arena.onReady { npc ->
            check(subject.hurt(subject.damageSources().mobAttack(attacker), 1.0F))
            val facts = checkNotNull(npc.worldView().observeEntity(subject.uuid)?.combat)
            check(facts.lastAttackerUuid == attacker.uuid && facts.lastAttackAgeTicks == 0L)
            arena.assign(npc, DefendTaskDefinition(npc.snapshot().dimensionId, arena.start, 24.0, subject.uuid,
                dutyTicks = 220, tactics = CombatTactics(preference = CombatWeaponPreference.MELEE)))
        }
        var selected = false
        arena.observe { npc, record ->
            selected = selected || record.primary.combat?.selectedTarget == attacker.uuid
            check(subject.health == subject.maxHealth - 1.0F) { "defender damaged the protected subject" }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && record.reason == TaskReason.DEFENSE_FINISHED) { TaskService.status(helper.level.server, npc.npcUuid).orEmpty() }
                check(selected && !attacker.isAlive && record.lastCombat?.confirmedKills == 1)
                check(TaskNavigator.distanceSquared(npc.snapshot().position, arena.start) <= 1.0)
                arena.succeed(npc, record)
            }
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 900, batch = "mission_patrol_reload")
    fun patrolPhysicallyVisitsDwellsReloadsAndReturnsWithoutRenewingItsBudget(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper)
        val first = NpcPosition(arena.start.x + 6, arena.start.y, arena.start.z)
        val second = NpcPosition(arena.start.x + 6, arena.start.y, arena.start.z + 5)
        arena.onReady { npc -> arena.assign(npc, PatrolTaskDefinition(npc.snapshot().dimensionId, arena.start, 24.0,
            listOf(first, second), dwellTicks = 20, reaction = PatrolReaction.PASSIVE, budget = TaskBudget(ticks = 800))) }
        var reloaded = false; var visitedFirst = false; var visitedSecond = false; var dwell = false
        arena.observe { npc, record ->
            val position = npc.snapshot().position
            visitedFirst = visitedFirst || TaskNavigator.distanceSquared(position, first) <= 1.0
            visitedSecond = visitedSecond || TaskNavigator.distanceSquared(position, second) <= 1.0
            dwell = dwell || (record.primary.combat?.dwellTicks ?: 0) > 0
            if (!reloaded && TaskNavigator.distanceSquared(position, arena.start) >= 4.0) {
                val budget = record.primary.remainingTicks
                val saved = TaskStore.forServer(helper.level.server).save(CompoundTag())
                check(BehaviorRuntimeService.reload().accepted)
                check(npc.snapshot().navigation == null && npc.snapshot().control == null)
                helper.level.server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(saved))
                val restored = checkNotNull(TaskStore.forServer(helper.level.server).get(npc.npcUuid))
                check(restored.id == record.id && restored.primary.remainingTicks == budget)
                reloaded = true
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && record.reason == TaskReason.PATROL_FINISHED) { TaskService.status(helper.level.server, npc.npcUuid).orEmpty() }
                check(reloaded && visitedFirst && visitedSecond && dwell && record.primary.combat?.patrolRounds == 1)
                check(TaskNavigator.distanceSquared(position, arena.start) <= 1.0)
                arena.succeed(npc, record)
            }
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 300, batch = "mission_no_ammunition")
    fun rangedOnlyWithoutAmmunitionCannotFallbackToCarriedSwordOrDamageTheTarget(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper)
        arena.body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.BOW))
        val target = arena.mob(EntityType.COW, 2.0, 0.0)
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.IRON_SWORD))
            arena.assign(npc, AttackTaskDefinition(npc.snapshot().dimensionId, target.uuid, arena.start,
                tactics = CombatTactics(preference = CombatWeaponPreference.RANGED, allowed = CombatWeaponAllowance.RANGED)))
        }
        arena.observe { npc, record ->
            check(target.health == target.maxHealth && arena.body.mainHandItem.`is`(Items.BOW))
            if (record.status.terminal) {
                check(record.status == TaskStatus.FAILED && record.reason == TaskReason.COMBAT_NO_PROGRESS)
                check(record.totalFailures == 3 && record.primary.remainingTicks in 1..599)
                arena.succeed(npc, record)
            }
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 300, batch = "mission_shield_policy")
    fun carriedShieldBlocksARealFrontalHitDuringTheActualMeleeCooldown(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper)
        arena.body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
        val target = arena.mob(EntityType.COW, 1.8, 0.0, 200.0)
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.SHIELD))
            arena.assign(npc, AttackTaskDefinition(npc.snapshot().dimensionId, target.uuid, arena.start,
                tactics = CombatTactics(preference = CombatWeaponPreference.MELEE)))
        }
        arena.observe { npc, record ->
            check(!record.status.terminal) { "shield policy ended before an actual block" }
            val use = npc.snapshot().itemUse
            if (use?.hand == NpcHand.OFF && use.elapsedTicks >= 6 && target.health < target.maxHealth) {
                check(arena.body.isBlocking && arena.body.offhandItem.`is`(Items.SHIELD))
                val health = arena.body.health
                val durability = arena.body.offhandItem.damageValue
                arena.body.hurt(helper.level.damageSources().mobAttack(target), 6.0F)
                check(arena.body.health == health) { "Behavior-held frontal shield did not block the actual hit" }
                check(arena.body.offhandItem.damageValue == durability + 7) { "blocked hit did not pay the real shield durability cost" }
                check(TaskService.cancel(helper.level.server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                arena.succeed(npc, record)
            }
        }
    }

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 120, batch = "mission_world_facts")
    fun liveRegistryTagsAndCandidateCoverRemainBoundedWorldObservations(helper: GameTestHelper) {
        // The suite defaults to peaceful; a real raider otherwise despawns before the first
        // grounded-body observation. Scope the required world difficulty to this single fixture.
        val previousDifficulty = helper.level.difficulty
        helper.level.server.setDifficulty(Difficulty.NORMAL, true)
        val arena = CombatGameTestArena(helper)
        val raider = arena.mob(EntityType.PILLAGER, 8.0, 0.0)
        val cow = arena.mob(EntityType.COW, 8.0, 5.0)
        arena.onReady { npc ->
            try {
            check(raider.isAlive && !raider.isRemoved) { "raider fixture despawned before observation" }
            val filter = NpcEntityTypeFilter.of(tagIds = setOf("minecraft:raiders"))
            val view = io.samcnpc.behavior.runtime.MeasuredWorldView(npc.worldView(), io.samcnpc.behavior.runtime.BehaviorWorkMetrics())
            val result = view.queryEntities(NpcEntityQuery(arena.start, 24.0, typeFilter = filter))
            check(result.any { it.uuid == raider.uuid } && result.none { it.uuid == cow.uuid }) { "live raiders query mismatch: filter=${filter.tagIds} result=$result" }
            check(view.observeEntity(raider.uuid, filter) != null && view.observeEntity(cow.uuid, filter) == null) { "filtered exact observation disagrees with live raider tag" }
            check(view.visibleFrom(arena.start, raider.uuid) == true) { "open candidate ray was not visible: ${view.visibleFrom(arena.start, raider.uuid)}" }
            for (y in 1..3) for (z in -2..2) helper.setBlock(BlockPos(4, y, z), Blocks.STONE)
            check(view.visibleFrom(arena.start, raider.uuid) == false) { "solid wall did not provide candidate cover: ${view.visibleFrom(arena.start, raider.uuid)}" }
            check(view.visibleFrom(NpcPosition(arena.start.x + 13, arena.start.y, arena.start.z), raider.uuid) == null) { "candidate eye outside 12-block limit was accepted" }
            } finally {
                arena.close()
                helper.level.server.setDifficulty(previousDifficulty, true)
            }
            helper.succeed()
        }
    }

}
