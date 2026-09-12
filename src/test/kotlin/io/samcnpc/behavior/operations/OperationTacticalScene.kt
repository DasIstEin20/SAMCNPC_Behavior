package io.samcnpc.behavior.operations

import io.samcnpc.behavior.combat.*
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionUtils
import net.minecraft.world.item.alchemy.Potions

internal enum class TacticalProbe { RANGED, HEALING, SHIELD }
internal class OperationTacticalScene(val scene: OperationScene,val probe: TacticalProbe) {
    private var started=false
    private var observedUse=false
    private var observedHealing=false
    private var shieldHit=false
    var outcome: String?=null
        private set
    fun tick() {
        if(outcome != null) return
        val s=scene
        if(!started) {
            if(!s.body.onGround()) return
            val target=s.enemy(EntityType.COW,if(probe == TacticalProbe.SHIELD) 2 else 8,0)
            if(probe == TacticalProbe.SHIELD) {
                checkNotNull(target.getAttribute(Attributes.MAX_HEALTH)).baseValue=200.0; target.health=200.0F
            }
            if(probe == TacticalProbe.RANGED) {
                s.give(Items.BOW); s.give(Items.ARROW,16); s.give(Items.IRON_HELMET)
            } else if(probe == TacticalProbe.HEALING) {
                s.give(PotionUtils.setPotion(ItemStack(Items.POTION),Potions.STRONG_HEALING))
                s.give(Items.IRON_SWORD); s.body.health=6.0F
            } else { s.give(Items.IRON_SWORD); s.give(Items.SHIELD) }
            val tactics=if(probe == TacticalProbe.RANGED) CombatTactics(CombatWeaponPreference.RANGED,CombatWeaponAllowance.RANGED,useShield=false)
                else CombatTactics(preference=CombatWeaponPreference.MELEE)
            s.assign(AttackTaskDefinition(s.npc.snapshot().dimensionId,target.uuid,s.start,budget=TaskBudget(1800),tactics=tactics))
            started=true
        }
        val snapshot=s.npc.snapshot()
        val record=s.record
        check(record.status != TaskStatus.FAILED && record.status != TaskStatus.CANCELLED) { "Client tactical $probe: ${record.report()}" }
        observedUse=observedUse || when(probe) {
            TacticalProbe.RANGED -> snapshot.rangedAttack != null
            TacticalProbe.HEALING -> snapshot.itemUse?.itemId == "minecraft:potion"
            TacticalProbe.SHIELD -> snapshot.itemUse?.hand == NpcHand.OFF
        }
        if(probe == TacticalProbe.HEALING) observedHealing=observedHealing || s.body.health >= 14.0F && record.primary.combat?.observedHealing == true
        if(probe == TacticalProbe.SHIELD && !shieldHit) {
            val use=snapshot.itemUse
            val target=s.enemyId?.let { s.level.getEntity(it) } as? LivingEntity
            if(use?.hand == NpcHand.OFF && use.elapsedTicks >= 6 && target != null && target.health < target.maxHealth) {
                check(s.body.isBlocking && s.body.offhandItem.`is`(Items.SHIELD))
                val health=s.body.health; val damage=s.body.offhandItem.damageValue
                s.body.hurt(s.level.damageSources().mobAttack(target),6.0F)
                check(s.body.health == health && s.body.offhandItem.damageValue == damage+7)
                shieldHit=true
                check(TaskService.cancel(s.server,s.npcId).status == NpcActionStatus.SUCCEEDED)
                s.requireReleased()
                outcome="SHIELD real_frontal_hit_blocked=true durability_paid=7 carried_shield=true"
            }
        }
        if(!record.status.terminal || probe == TacticalProbe.SHIELD) return
        s.requireCompleted(); check(observedUse && s.enemyId?.let { s.level.getEntity(it) }?.isAlive != true)
        if(probe == TacticalProbe.RANGED) {
            check(s.carried("minecraft:arrow") in 0..15 && s.carried("minecraft:bow") == 1)
            check(s.body.getItemBySlot(EquipmentSlot.HEAD).`is`(Items.IRON_HELMET))
            outcome="RANGED actual_projectile_defeat=true carried_arrows_consumed=true helmet_equipped=true"
        } else {
            check(observedHealing && record.primary.combat?.healingUses == 1)
            check(s.carried("minecraft:potion") == 0 && s.carried("minecraft:glass_bottle") == 1 && s.carried("minecraft:iron_sword") == 1)
            outcome="HEALING potion_consumed=1 bottle_returned=1 recovered_and_counterattacked=true actual_defeat=true"
        }
    }
}
