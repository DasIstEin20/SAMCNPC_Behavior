package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.registries.ForgeRegistries

internal class CombatGameTestArena(val helper: GameTestHelper, summoner: net.minecraft.server.level.ServerPlayer? = null) {
    private val server = helper.level.server
    private val origin = helper.absolutePos(BlockPos.ZERO)
    val start = NpcPosition(origin.x + 0.5, origin.y + 1.0, origin.z + 0.5)
    val body: LivingEntity
    private val others = mutableListOf<LivingEntity>()
    private var started = false
    private var done = false
    private var setup: ((NpcFacade) -> Unit)? = null
    private var checkTick: ((NpcFacade, TaskRecord) -> Unit)? = null
    init {
        for (x in -3..30) for (z in -10..12) for (y in 0..5) helper.setBlock(BlockPos(x, y, z), if (y == 0) Blocks.STONE else Blocks.AIR)
        if (summoner == null) {
            val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
            body = checkNotNull(type.create(helper.level)) as LivingEntity
            body.moveTo(start.x, start.y, start.z, -90.0F, 0.0F)
            check(helper.level.addFreshEntity(body))
        } else {
            summoner.teleportTo(helper.level, start.x, start.y + 3.0, start.z + 8.0, -90.0F, 0.0F)
            val summoned = CoreNpcApi.service(server).summon(NpcSummonRequest(summoner.uuid, "AmendProof", helper.level.dimension().location().toString(), start, -90.0F))
            check(summoned.result.status == NpcActionStatus.SUCCEEDED) { summoned.result.detail }
            body = checkNotNull(helper.level.getEntity(checkNotNull(summoned.handle).npcUuid)) as LivingEntity
        }
        checkNotNull(body.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
        helper.onEachTick {
            if (done || !started && !body.onGround()) return@onEachTick
            val service = CoreNpcApi.service(server)
            val npc = checkNotNull(service.find(body.uuid)?.let(service::runtime))
            try {
                if (!started) { started = true; checkNotNull(setup)(npc) }
                if (!done && checkTick != null) {
                    val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
                    checkNotNull(checkTick)(npc, record)
                }
            } catch (error: Exception) {
                // GameTest's default reporter prints only the message, losing the failing invariant.
                val logger = com.mojang.logging.LogUtils.getLogger()
                logger.error("Physical task assertion failed at tick {} for NPC {}", helper.tick, body.uuid, error)
                // Cleanup may already have removed/replaced the fixture body. Preserve the original assertion.
                val current = service.find(body.uuid)?.let(service::runtime)
                if (current != null) {
                    logger.error("Physical failure inventory={} drops={}", current.inventoryContents().filter { !it.stack.isEmpty },
                        helper.level.getEntitiesOfClass(ItemEntity::class.java, body.boundingBox.inflate(16.0)).map { "${it.item}@${it.position()}" })
                }
                throw error
            }
        }
    }
    fun onReady(action: (NpcFacade) -> Unit) { setup = action }
    fun observe(action: (NpcFacade, TaskRecord) -> Unit) { checkTick = action }
    fun <T : Mob> mob(type: EntityType<T>, x: Double, z: Double, health: Double? = null): T {
        val mob = checkNotNull(type.create(helper.level)); mob.isNoAi = true
        mob.moveTo(start.x + x, start.y, start.z + z)
        checkNotNull(mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue = 1.0
        if (health != null) checkNotNull(mob.getAttribute(Attributes.MAX_HEALTH)).baseValue = health
        mob.health = mob.maxHealth; check(helper.level.addFreshEntity(mob)); others.add(mob)
        return mob
    }
    fun give(npc: NpcFacade, stack: ItemStack) {
        val drop = ItemEntity(helper.level, body.x, body.y, body.z, stack); drop.setNoPickUpDelay()
        check(helper.level.addFreshEntity(drop))
        check(npc.pickupItem(drop.uuid).status == NpcActionStatus.SUCCEEDED) { "fixture carried-item pickup rejected" }
    }
    fun assign(npc: NpcFacade, definition: TaskDefinition) {
        val result = TaskService.assign(server, npc, definition)
        check(result.status == NpcActionStatus.SUCCEEDED) { "mission assignment failed: $result" }
    }
    fun succeed(npc: NpcFacade, record: TaskRecord) {
        check(TaskCodec.read(TaskCodec.write(record)).report() == record.report())
        val snapshot = npc.snapshot()
        check(snapshot.navigation == null && snapshot.rangedAttack == null && snapshot.itemUse == null)
        check(BehaviorRuntimeService.assignedPacks(server, body.uuid).isEmpty())
        close(); helper.succeed()
    }
    fun close() { done = true; body.discard(); others.forEach { it.discard() } }
}
