package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.NpcEntityTypeFilter
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Items

internal object OperationCombatCases {
    fun prepare(s: OperationScene) {
        val dim=s.npc.snapshot().dimensionId
        val filter=NpcEntityTypeFilter.of(setOf("minecraft:husk"))
        val budget=TaskBudget(1800)
        s.give(Items.IRON_SWORD)
        when(s.kind) {
            OperationKind.ATTACK -> {
                val enemy=s.enemy(EntityType.HUSK,12,0)
                s.assign(AttackTaskDefinition(dim,enemy.uuid,s.start,leash=30.0,budget=budget))
            }
            OperationKind.AREA_ATTACK -> {
                s.enemy(EntityType.HUSK,12,0)
                s.assign(AreaAttackTaskDefinition(dim,s.start,30.0,filter,1,budget=budget))
            }
            OperationKind.DEFEND -> {
                s.enemy(EntityType.HUSK,12,0)
                s.assign(DefendTaskDefinition(dim,s.start,30.0,filter=filter,dutyTicks=300,budget=budget))
            }
            OperationKind.PATROL -> s.assign(PatrolTaskDefinition(dim,s.start,30.0,listOf(s.point(12,0),s.point(20,6)),
                rounds=1,dwellTicks=40,reaction=PatrolReaction.PASSIVE,budget=budget))
            else -> error("${s.kind} is not a combat case")
        }
    }
    fun checkpoint(s: OperationScene): Boolean = when(s.kind) {
        OperationKind.ATTACK, OperationKind.AREA_ATTACK -> (s.enemyId?.let { s.level.getEntity(it) } as? LivingEntity)?.let { it.isAlive && it.health < 30.0F } == true
        OperationKind.DEFEND -> s.record.primary.combat?.dutyTicks?.let { it in 1..240 } == true
        OperationKind.PATROL -> s.record.primary.combat?.let { it.waypointReached && it.dwellTicks in 1..39 } == true
        else -> false
    }
    // CombatMission uses its explicit 1-block return envelope; other operations retain 0.75.
    fun verify(s: OperationScene) {
        s.requireCompleted()
        when(s.kind) {
            OperationKind.ATTACK -> {
                check(s.enemyId?.let { s.level.getEntity(it) }?.isAlive != true)
                check(s.record.reason == TaskReason.TARGET_DEFEATED)
            }
            OperationKind.AREA_ATTACK -> {
                check(s.enemyId?.let { s.level.getEntity(it) }?.isAlive != true)
                check(s.record.primary.combat?.defeatedTargets == setOf(s.enemyId)); s.requireReturned(1.0)
            }
            OperationKind.DEFEND -> {
                check(s.record.primary.combat?.dutyTicks == 0)
                check(s.enemyId?.let { s.level.getEntity(it) }?.isAlive != true && s.record.lastCombat?.confirmedKills == 1)
                s.requireReturned(1.0)
            }
            OperationKind.PATROL -> { check(s.record.primary.combat?.patrolRounds == 1); s.requireReturned(1.0) }
            else -> error("${s.kind} is not a combat result")
        }
    }
}
