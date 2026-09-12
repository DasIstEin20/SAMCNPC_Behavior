package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraftforge.registries.ForgeRegistries

/** Isolated pond; effects are driven through published Core APIs and the real Behavior tick. */
internal class FishingGameTestArena(val helper: GameTestHelper) {
    val server = helper.level.server
    val body: LivingEntity
    val start: NpcPosition
    val water: NpcBlockPosition
    val dimension = helper.level.dimension().location().toString()
    val box = AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(20.0, 12.0, 20.0)
    var cleanup: () -> Unit = {}
    private var ended = false
    private val extra = mutableListOf<LivingEntity>()
    val npc: NpcFacade get() {
        val service = CoreNpcApi.service(server)
        return checkNotNull(service.find(body.uuid)?.let(service::runtime))
    }
    init {
        for (x in 1..18) for (z in 1..18) for (y in 0..10) {
            helper.setBlock(BlockPos(x,y,z), if (y <= 2) Blocks.STONE else Blocks.AIR)
        }
        fill(Blocks.WATER)
        val at = helper.absolutePos(BlockPos(4,3,9))
        start = NpcPosition(at.x+0.5, at.y.toDouble(), at.z+0.5)
        val target = helper.absolutePos(BlockPos(11,2,9))
        water = NpcBlockPosition(target.x,target.y,target.z)
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core","npc")))
        body = checkNotNull(type.create(helper.level)) as LivingEntity
        body.moveTo(start.x,start.y,start.z,-90.0F,0.0F)
        checkNotNull(body.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue=1.0
        check(helper.level.addFreshEntity(body))
    }
    fun fill(block: net.minecraft.world.level.block.Block) {
        for (x in 6..17) for (z in 6..12) for (y in 1..2) helper.setBlock(BlockPos(x,y,z),block)
    }
    fun give(stack: ItemStack) {
        val drop = ItemEntity(helper.level,body.x,body.y,body.z,stack)
        drop.setNoPickUpDelay(); check(helper.level.addFreshEntity(drop))
        check(npc.pickupItem(drop.uuid).status == NpcActionStatus.SUCCEEDED)
    }
    fun rod(lastUse: Boolean = false, lure: Boolean = true) {
        val stack=ItemStack(Items.FISHING_ROD)
        if (lure) stack.enchant(Enchantments.FISHING_SPEED,3)
        if (lastUse) stack.damageValue=stack.maxDamage-1
        give(stack)
    }
    fun enemy(): LivingEntity {
        val enemy=checkNotNull(EntityType.ZOMBIE.create(helper.level))
        enemy.isNoAi=true; enemy.setPersistenceRequired()
        enemy.moveTo(start.x-1.0,start.y,start.z+2.0)
        checkNotNull(enemy.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue=1.0
        enemy.health=4.0F;check(helper.level.addFreshEntity(enemy));extra.add(enemy)
        return enemy
    }
    fun interrupt(enemy: LivingEntity) {
        check(TaskService.interrupt(server,body.uuid,AttackTaskDefinition(dimension,enemy.uuid,start,16.0,budget=TaskBudget(400))).status == NpcActionStatus.SUCCEEDED)
    }
    fun assign(catches: Int, ticks: Int = 2400, pickupTicks: Int = 240) {
        val definition=FishingTaskDefinition(dimension,water,start,catches,start,pickupWaitTicks=pickupTicks,budget=TaskBudget(ticks))
        val result=TaskService.assign(server,npc,definition)
        check(result.status == NpcActionStatus.SUCCEEDED) { result.detail }
    }
    fun run(setup: () -> Unit, step: (TaskRecord) -> Unit) {
        var started=false
        helper.onEachTick {
            if (ended) return@onEachTick
            if (!started && !body.onGround()) return@onEachTick
            try {
                if (!started) { started=true;setup() }
                step(checkNotNull(TaskStore.forServer(server).get(body.uuid)))
            } catch (error: Exception) {
                com.mojang.logging.LogUtils.getLogger().error("Fishing task failure tick={} npc={} task={} inventory={} hooks={}",
                    helper.tick,body.uuid,TaskStore.forServer(server).get(body.uuid)?.report(),npc.inventoryContents().filter { !it.stack.isEmpty }.map { it.slot to it.stack },hooks(),error)
                close();throw error
            }
        }
    }
    fun hooks() = helper.level.getEntities(null,box) {
        it.isAlive && ForgeRegistries.ENTITY_TYPES.getKey(it.type)?.toString() == "samcnpc_core:npc_fishing_hook"
    }
    fun released() {
        val snapshot=npc.snapshot()
        check(snapshot.fishing == null && snapshot.navigation == null && snapshot.control == null && snapshot.itemUse == null && snapshot.rangedAttack == null)
        check(hooks().isEmpty())
    }
    fun pass(record: TaskRecord) {
        check(TaskCodec.read(TaskCodec.write(record)).report() == record.report())
        released();check(BehaviorRuntimeService.assignedPacks(server,body.uuid).isEmpty())
        close();helper.succeed()
    }
    private fun close() {
        if (ended) return
        ended=true;cleanup();body.discard();extra.forEach { it.discard() };hooks().forEach { it.discard() }
    }
}
