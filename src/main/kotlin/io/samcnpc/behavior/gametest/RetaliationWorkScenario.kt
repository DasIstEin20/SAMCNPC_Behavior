package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.runtime.BehaviorTargetMemory
import io.samcnpc.behavior.task.TaskNavigator
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.level.block.Blocks
import java.util.UUID

/** Actual damage/health and control lifecycle, shared by GameTest and the connected client. */
internal class RetaliationWorkScenario(private val level: ServerLevel, player: ServerPlayer, private val origin: BlockPos) {
    private val service = CoreNpcApi.service(level.server)
    val npcUuid: UUID
    private val body: LivingEntity
    private val first: LivingEntity
    private val second: LivingEntity
    var outcome: String? = null
        private set
    var phase = 0
        private set
    private var age = 0
    private var ticks = 0
    private var lastHitTick = -20
    private var acceptedHits = 0
    private var counterDamage = 0.0F
    private var savedHealth = 0.0F
    private var positionBefore = NpcPosition(0.0, 0.0, 0.0)

    init {
        for (x in -5..34) for (z in -7..8) for (y in 0..6) level.setBlock(origin.offset(x, y, z),
            (if (y == 0) Blocks.STONE else Blocks.AIR).defaultBlockState(), 3)
        player.teleportTo(level, origin.x - 3.0, origin.y + 2.5, origin.z + 5.0, -135.0F, 10.0F)
        val summoned = service.summon(NpcSummonRequest(player.uuid, "RetaliationProof", level.dimension().location().toString(), position(0.5, 1.0, 0.5), -90.0F))
        check(summoned.result.status == NpcActionStatus.SUCCEEDED)
        npcUuid = checkNotNull(summoned.handle).npcUuid
        body = checkNotNull(level.getEntity(npcUuid)) as LivingEntity
        checkNotNull(body.getAttribute(Attributes.MAX_HEALTH)).baseValue = 200.0
        checkNotNull(body.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
        body.health = body.maxHealth
        body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
        first = attacker(2.3)
        second = attacker(6.5)
    }

    fun tick() {
        if (outcome != null) return
        check(++ticks < 1000) { "retaliation world scenario timed out phase=$phase diagnostic=${BehaviorRuntimeService.diagnostic(npcUuid)}" }
        val npc = checkNotNull(service.find(npcUuid)?.let(service::runtime))
        val snapshot = npc.snapshot()
        if (phase == 0 && !snapshot.onGround) return
        age++
        when (phase) {
            0 -> {
                if (age == 1) hit(first)
                check(BehaviorRuntimeService.assignedPacks(level.server, npcUuid).isEmpty())
                check(first.health == first.maxHealth && snapshot.control == null && snapshot.navigation == null) { "unassigned NPC retaliated" }
                if (age >= 30) { assign(true); advance() }
            }
            1 -> {
                check(BehaviorTargetMemory.targetFor(npcUuid) == null && first.health == first.maxHealth) { "assignment replayed a hit from before assignment" }
                if (age >= 20) advance()
            }
            2 -> {
                if (age % 12 == 1) hit(first)
                if (age > 5) check(BehaviorTargetMemory.targetFor(npcUuid) == first.uuid)
                if (age >= 120) {
                    counterDamage = first.maxHealth - first.health
                    check(counterDamage > 5.0F && acceptedHits >= 11) { "continuous accepted damage blocked real attacks: damage=$counterDamage hits=$acceptedHits" }
                    advance()
                }
            }
            3 -> {
                if (age % 12 == 1) hit(second)
                check(BehaviorTargetMemory.targetFor(npcUuid) == first.uuid) { "new attacker replaced a still valid target" }
                check(second.health == second.maxHealth) { "stable selection attacked the competing source" }
                if (age >= 60) {
                    first.moveTo(origin.x + 28.5, origin.y + 1.0, origin.z + 0.5)
                    advance()
                }
            }
            4 -> {
                if (age >= 3) check(BehaviorTargetMemory.targetFor(npcUuid) == null && snapshot.control == null && snapshot.navigation == null) { "leash did not release retaliation controls" }
                if (age >= 30) {
                    check(BehaviorTargetMemory.diagnostic(npcUuid)?.contains("leash") == true)
                    positionBefore = snapshot.position
                    hit(second)
                    advance()
                }
            }
            5 -> {
                check(age < 100) { "new hit did not restart a bounded approach" }
                if (snapshot.control != null && TaskNavigator.distanceSquared(positionBefore, snapshot.position) > 0.04) {
                    check(BehaviorRuntimeService.reload().accepted)
                    check(npc.snapshot().control == null && npc.snapshot().navigation == null && BehaviorTargetMemory.targetFor(npcUuid) == null)
                    savedHealth = second.health
                    advance()
                }
            }
            6 -> {
                check(BehaviorTargetMemory.targetFor(npcUuid) == null && snapshot.control == null && snapshot.navigation == null)
                check(second.health == savedHealth) { "old damage restarted combat after reload" }
                if (age >= 30) { hit(second); advance() }
            }
            7 -> {
                check(age < 150) { "fresh hit did not cause an actual post-reload counterattack" }
                if (second.health < savedHealth - 1.0F) {
                    assign(false)
                    check(npc.snapshot().control == null && npc.snapshot().navigation == null && BehaviorTargetMemory.targetFor(npcUuid) == null)
                    savedHealth = second.health
                    advance()
                }
            }
            8 -> {
                check(snapshot.control == null && snapshot.navigation == null && second.health == savedHealth) { "unassigned retaliation kept fighting" }
                if (age >= 35) {
                    outcome = "Retaliation: unassigned idle under damage -> old-hit baseline -> continuous accepted hits with physical counterattacks -> stable target under a second attacker -> 24-block leash releases -> fresh hit approaches -> reload stops without replay -> fresh hit fights -> unassign stops; acceptedHits=$acceptedHits counterDamage=$counterDamage ticks=$ticks"
                }
            }
        }
    }

    fun close() { body.discard(); first.discard(); second.discard() }
    private fun hit(attacker: LivingEntity) {
        check(ticks - lastHitTick >= 12) { "fixture must respect actual invulnerability frames" }
        check(body.hurt(body.damageSources().mobAttack(attacker), 0.25F)) { "fixture damage was rejected" }
        lastHitTick = ticks
        acceptedHits++
    }
    private fun assign(enabled: Boolean) {
        check(BehaviorRuntimeService.assignPacks(level.server, npcUuid, if (enabled) listOf("samcnpc:retaliate") else emptyList()).status == NpcActionStatus.SUCCEEDED)
    }
    private fun attacker(x: Double): LivingEntity {
        // Passive fixture sources survive peaceful worlds; all damage still goes through hurt().
        val entity = checkNotNull(EntityType.COW.create(level))
        entity.isNoAi = true
        checkNotNull(entity.getAttribute(Attributes.MAX_HEALTH)).baseValue = 200.0
        checkNotNull(entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
        entity.health = entity.maxHealth
        entity.moveTo(origin.x + x, origin.y + 1.0, origin.z + 0.5)
        check(level.addFreshEntity(entity))
        return entity
    }
    private fun advance() { phase++; age = 0 }
    private fun position(x: Double, y: Double, z: Double) = NpcPosition(origin.x + x, origin.y + y, origin.z + z)
}
