package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object RetaliationGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 220, batch = "retaliation_continuous_damage")
    fun repeatedAcceptedHitsDoNotStarveThePhysicalCounterattack(helper: GameTestHelper) {
        for (x in 0..10) for (z in 0..8) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val feet = helper.absolutePos(BlockPos(3, 1, 4))
        body.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, -90.0F, 0.0F)
        checkNotNull(body.getAttribute(Attributes.MAX_HEALTH)).baseValue = 200.0
        checkNotNull(body.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
        body.health = body.maxHealth
        check(helper.level.addFreshEntity(body))
        val attacker = checkNotNull(EntityType.COW.create(helper.level))
        attacker.isNoAi = true
        checkNotNull(attacker.getAttribute(Attributes.MAX_HEALTH)).baseValue = 200.0
        checkNotNull(attacker.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
        attacker.health = attacker.maxHealth
        attacker.moveTo(body.x + 1.8, body.y, body.z, 90.0F, 0.0F)
        check(helper.level.addFreshEntity(attacker))
        var age = 0
        var acceptedHits = 0
        var started = false
        var done = false
        helper.onEachTick {
            if (done || !body.onGround()) return@onEachTick
            if (!started) {
                check(BehaviorRuntimeService.assignPacks(helper.level.server, body.uuid, listOf("samcnpc:retaliate")).status == NpcActionStatus.SUCCEEDED)
                started = true
            }
            // Twelve ticks allow vanilla invulnerability frames; no fixture bypasses hurt().
            if (age % 12 == 0) {
                check(body.hurt(body.damageSources().mobAttack(attacker), 0.25F)) { "fixture damage was rejected" }
                acceptedHits++
                val service = CoreNpcApi.service(helper.level.server)
                val runtime = checkNotNull(service.find(body.uuid)?.let(service::runtime))
                val observation = checkNotNull(runtime.worldView().observeEntity(attacker.uuid)) { "fixture attacker left the actual world" }
                check(observation.alive && observation.combat?.permitted == true && observation.combat?.visible == true) { "invalid fixture attacker: $observation" }
            }
            age++
            if (age == 100) {
                check(acceptedHits >= 8 && body.health < body.maxHealth)
                check(attacker.health < attacker.maxHealth - 1.0F) {
                    "continuous hits starved retaliation: accepted=$acceptedHits attackerHealth=${attacker.health} diagnostic=${BehaviorRuntimeService.diagnostic(body.uuid)}"
                }
                done = true
                body.discard()
                attacker.discard()
                helper.succeed()
            }
        }
    }
}
