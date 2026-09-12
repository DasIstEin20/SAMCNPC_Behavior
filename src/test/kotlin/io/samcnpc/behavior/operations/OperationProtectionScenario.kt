package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import java.util.UUID

internal enum class OperationProtectionKind {
    DEFEND_SUMMONER, DEFEND_NPC, PATROL_SUMMONER, PATROL_SUPPORT, PATROL_RETALIATE, PATROL_AREA,
}

/** Native fixture subjects and damage; every selected enemy must die through the NPC's real attack. */
internal class OperationProtectionScenario(server: MinecraftServer, val kind: OperationProtectionKind) {
    private val origin = BlockPos(1800 + kind.ordinal * 64, 80, 1000)
    private val actor: OperationActor
    val scene: OperationScene
    private val protectedNpc: UUID?
    private var enemy: LivingEntity? = null
    private var bystander: LivingEntity? = null
    private var protectedHealth = 0.0F
    private var ticks = 0
    private var stage = 0
    private var selected = false
    private var visitedFirst = false
    private var visitedSecond = false
    private var sawDwell = false
    private var actorClosed = false
    private var quietTicks = 0
    private var healthAtSubjectLoss = 0.0F
    var complete = false
        private set
    val npcIds get() = listOfNotNull(scene.npcId, protectedNpc)
    private val patrol get() = kind != OperationProtectionKind.DEFEND_SUMMONER && kind != OperationProtectionKind.DEFEND_NPC

    init {
        val level = server.overworld()
        for (x in -3..32) for (z in -8..12) for (y in 0..6)
            level.setBlock(origin.offset(x, y, z), (if (y == 0) Blocks.STONE else Blocks.AIR).defaultBlockState(), 3)
        actor = OperationActor(level, UUID.randomUUID())
        actor.player.teleportTo(level, origin.x + 3.5, origin.y + 1.0, origin.z + 3.5, 0.0F, 0.0F)
        actor.player.setGameMode(GameType.SURVIVAL)
        checkNotNull(actor.player.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
        val npcId = summon(server, "O4-$kind", 0, 0)
        scene = OperationScene(level, if (patrol) OperationKind.PATROL else OperationKind.DEFEND, origin, npcId)
        protectedNpc = if (kind == OperationProtectionKind.DEFEND_NPC) summon(server, "O4ProtectedNpc", 3, 3) else null
    }

    fun tick() {
        if (complete) return
        check(++ticks < 1200) { "$kind protection timed out stage=$stage" }
        if (!scene.loaded || !scene.body.onGround()) return
        val subject = protectedNpc?.let { scene.level.getEntity(it) as? LivingEntity } ?: actor.player
        if (protectedNpc != null && subject.uuid != protectedNpc) return
        check(scene.npc.snapshot().summonerUuid == actor.player.uuid)
        if (stage >= 2) {
            subjectLoss(subject)
            return
        }
        if (stage == 0) {
            // Let the native ServerPlayer spawn-invulnerability timer expire; do not force an accepted hit.
            // This embedded player has no client movement packet to set its ground flag.
            if (ticks < 100 || protectedNpc != null && !subject.onGround()) return
            checkNotNull(subject.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
            scene.give(Items.IRON_SWORD)
            val attacker = scene.enemy(EntityType.HUSK, 6, 3)
            enemy = attacker
            val excluded = scene.enemy(EntityType.SHEEP, 5, 6)
            bystander = excluded
            scene.enemyId = attacker.uuid
            when (kind) {
                OperationProtectionKind.DEFEND_SUMMONER, OperationProtectionKind.DEFEND_NPC,
                OperationProtectionKind.PATROL_SUMMONER -> {
                    check(subject.hurt(subject.damageSources().mobAttack(attacker), 1.0F)) { "$kind native subject hit was rejected" }
                    val facts = checkNotNull(scene.npc.worldView().observeEntity(subject.uuid)?.combat)
                    check(facts.lastAttackerUuid == attacker.uuid && facts.lastAttackAgeTicks == 0L)
                }
                OperationProtectionKind.PATROL_RETALIATE -> check(scene.body.hurt(scene.body.damageSources().mobAttack(attacker), 1.0F))
                OperationProtectionKind.PATROL_SUPPORT, OperationProtectionKind.PATROL_AREA -> Unit
            }
            protectedHealth = subject.health
            val dim = scene.npc.snapshot().dimensionId
            val filter = NpcEntityTypeFilter.of(setOf("minecraft:husk"))
            val definition = if (!patrol) DefendTaskDefinition(dim, scene.start, 30.0, subject.uuid,
                filter = filter, dutyTicks = 300, budget = TaskBudget(1000))
            else PatrolTaskDefinition(dim, scene.start, 30.0, listOf(scene.point(12, 0), scene.point(20, 6)),
                rounds = 1, dwellTicks = 20, reaction = when (kind) {
                    OperationProtectionKind.PATROL_SUMMONER -> PatrolReaction.PROTECT_SUMMONER
                    OperationProtectionKind.PATROL_SUPPORT -> PatrolReaction.SUPPORT
                    OperationProtectionKind.PATROL_RETALIATE -> PatrolReaction.RETALIATE
                    OperationProtectionKind.PATROL_AREA -> PatrolReaction.AREA
                    else -> error("Unexpected patrol $kind")
                }, subjectUuid = if (kind == OperationProtectionKind.PATROL_SUMMONER) subject.uuid else null,
                supportTargetUuid = if (kind == OperationProtectionKind.PATROL_SUPPORT) attacker.uuid else null,
                filter = filter, budget = TaskBudget(1000))
            scene.assign(definition)
            stage = 1
            return
        }
        val attacker = checkNotNull(enemy)
        val excluded = checkNotNull(bystander)
        check(subject.isAlive && subject.health >= protectedHealth) { "$kind damaged its protected subject" }
        check(excluded.isAlive && excluded.health == excluded.maxHealth) { "$kind damaged an excluded bystander" }
        val record = scene.record
        val combat = checkNotNull(record.primary.combat)
        selected = selected || combat.selectedTarget == attacker.uuid
        check(combat.selectedTarget == null || combat.selectedTarget == attacker.uuid)
        val position = scene.npc.snapshot().position
        visitedFirst = visitedFirst || TaskNavigator.distanceSquared(position, scene.point(12, 0)) <= 1.0
        visitedSecond = visitedSecond || TaskNavigator.distanceSquared(position, scene.point(20, 6)) <= 1.0
        sawDwell = sawDwell || combat.dwellTicks > 0
        if (!record.status.terminal) return
        scene.requireCompleted()
        check(record.reason == if (patrol) TaskReason.PATROL_FINISHED else TaskReason.DEFENSE_FINISHED) { record.report() }
        check(selected && !attacker.isAlive && record.lastCombat?.confirmedKills == 1 && record.lastCombat?.targetUuid == attacker.uuid) { "$kind never confirmed its actual attacker defeat: ${record.report()}" }
        if (patrol) {
            check(visitedFirst && visitedSecond && sawDwell && combat.patrolRounds == 1) { "$kind did not resume its route and both dwells" }
            if (kind == OperationProtectionKind.PATROL_SUPPORT) check(combat.supportFinished)
        } else check(combat.dutyTicks == 0)
        scene.requireReturned(1.0)
        if (!patrol) {
            val next = scene.enemy(EntityType.HUSK, 6, 3)
            enemy = next
            check(subject.hurt(subject.damageSources().mobAttack(next), 1.0F))
            scene.assign(DefendTaskDefinition(scene.npc.snapshot().dimensionId, scene.start, 30.0, subject.uuid,
                filter = NpcEntityTypeFilter.of(setOf("minecraft:husk")), dutyTicks = 300, budget = TaskBudget(1000)))
            stage = 2
            return
        }
        finish()
    }

    private fun subjectLoss(subject: LivingEntity) {
        val attacker = checkNotNull(enemy)
        val record = scene.record
        if (stage == 2) {
            check(!record.status.terminal) { "Defense ended before native subject loss: ${record.report()}" }
            if (record.primary.combat?.selectedTarget != attacker.uuid || attacker.health == attacker.maxHealth) return
            check(attacker.isAlive)
            healthAtSubjectLoss = attacker.health
            if (kind == OperationProtectionKind.DEFEND_SUMMONER) {
                actor.close()
                actorClosed = true
                check(scene.server.playerList.getPlayer(actor.player.uuid) == null)
            } else {
                // The last supported floor cell is outside the 30-block mission boundary.
                subject.moveTo(origin.x + 32.5, origin.y + 1.0, origin.z + 3.5, 0.0F, 0.0F)
            }
            stage = 3
            return
        }
        check(attacker.isAlive && attacker.health == healthAtSubjectLoss) { "Defense kept attacking after its subject became unavailable" }
        if (!record.status.terminal) return
        check(record.status == TaskStatus.CANCELLED && record.reason == TaskReason.SUBJECT_UNAVAILABLE) { record.report() }
        scene.requireReleased()
        if (++quietTicks < 20) return
        finish()
    }

    private fun finish() {
        scene.close()
        bystander?.discard()
        protectedNpc?.let { scene.level.getEntity(it)?.discard() }
        if (!actorClosed) actor.close()
        enemy = null
        bystander = null
        complete = true
    }

    private fun summon(server: MinecraftServer, name: String, x: Int, z: Int): UUID {
        val result = CoreNpcApi.service(server).summon(NpcSummonRequest(actor.player.uuid, name,
            server.overworld().dimension().location().toString(),
            NpcPosition(origin.x + x + 0.5, origin.y + 1.0, origin.z + z + 0.5), -90.0F))
        check(result.result.status == NpcActionStatus.SUCCEEDED) { result.result }
        return checkNotNull(result.handle).npcUuid
    }
}
