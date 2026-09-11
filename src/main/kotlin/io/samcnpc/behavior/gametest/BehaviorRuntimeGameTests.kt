package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.model.BehaviorReadContext
import io.samcnpc.behavior.model.CompiledPack
import io.samcnpc.behavior.registry.BehaviorDefinitions
import io.samcnpc.behavior.runtime.BehaviorDecisionPlan
import io.samcnpc.core.api.CoreNpcApi
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object BehaviorRuntimeGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 160, batch = "runtime_channel_arbitration")
    fun registeredMovementCannotMoveTheBodyWhileLookIsReserved(helper: GameTestHelper) {
        for (x in 0..11) for (z in 0..9) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val feet = helper.absolutePos(BlockPos(2, 1, 4))
        body.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, -90.0F, 0.0F)
        check(helper.level.addFreshEntity(body))
        val target = checkNotNull(EntityType.ARMOR_STAND.create(helper.level))
        target.moveTo(feet.x + 6.5, feet.y.toDouble(), feet.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(target))
        val movement = pack("test:movement", 10, "samcnpc:move_to_target", """{"speed":1.0,"stopDistance":1.0}""")
        val looking = pack("test:looking", 20, "samcnpc:look_at_target", "{}")
        val contested = BehaviorDecisionPlan(listOf(movement, looking))
        val free = BehaviorDecisionPlan(listOf(movement))
        val cooldowns = mutableMapOf<String, Long>()
        var origin: Vec3? = null
        var ticks = 0
        var finished = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!body.onGround()) return@onEachTick
            val start = origin ?: body.position().also { origin = it }
            val world = npc.worldView()
            val observation = checkNotNull(world.observeEntity(target.uuid))
            val context = BehaviorReadContext(npc.snapshot(), null, observation)
            val plan = if (ticks < 20) contested else free
            val result = plan.tick(context, cooldowns) { intent -> intent.action.handler.execute(npc, world, context) }
            if (ticks < 20) {
                check(body.position().distanceToSqr(start) < 0.0025) { "Rejected movement changed the real body: ${body.position()} from $start" }
                check(result.selectedLabels.none { it.contains("move_to_target") }) { "LOOK conflict still admitted movement" }
            }
            ticks++
            if (ticks >= 50 && body.position().distanceToSqr(target.position()) < start.distanceToSqr(target.position()) - 9.0) {
                npc.stopControl()
                finished = true
                body.discard()
                target.discard()
                helper.succeed()
            }
        }
    }

    private fun pack(id: String, priority: Int, action: String, args: String): CompiledPack {
        val json = """{"schemaVersion":1,"id":"$id","description":"runtime channels","priority":0,
            "channels":["movement","look"],"rules":[{"id":"act","priority":$priority,
            "when":{"test":{"condition":"samcnpc:always"}},"actions":[{"action":"$action","args":$args}]}]}"""
        val result = BehaviorDefinitions.compiler.compile(id, json)
        return checkNotNull(result.pack) { result.report.messages.joinToString() }
    }
}
