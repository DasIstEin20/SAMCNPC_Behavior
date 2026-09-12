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
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.registries.ForgeRegistries

internal class ExplorerGameTestArena(val helper: GameTestHelper) {
    val server=helper.level.server
    val dimension=helper.level.dimension().location().toString()
    val start: NpcPosition
    val body: LivingEntity
    private var ended=false
    private val extra=mutableListOf<LivingEntity>()
    val npc: NpcFacade get() {
        val service=CoreNpcApi.service(server)
        return checkNotNull(service.find(body.uuid)?.let(service::runtime))
    }
    init {
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL,true)
        for (x in 1..46) for (z in 1..46) for (y in 0..11) helper.setBlock(BlockPos(x,y,z),if (y <= 3) Blocks.STONE else Blocks.AIR)
        val at=helper.absolutePos(BlockPos(24,4,24))
        start=NpcPosition(at.x+0.5,at.y.toDouble(),at.z+0.5)
        val type=checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core","npc")))
        body=checkNotNull(type.create(helper.level)) as LivingEntity
        body.moveTo(start.x,start.y,start.z,-90.0F,0.0F)
        checkNotNull(body.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue=1.0
        check(helper.level.addFreshEntity(body))
    }
    fun give(stack: ItemStack) {
        val drop=ItemEntity(helper.level,body.x,body.y,body.z,stack);drop.setNoPickUpDelay()
        check(helper.level.addFreshEntity(drop));check(npc.pickupItem(drop.uuid).status == NpcActionStatus.SUCCEEDED)
    }
    fun assign(cells: Int=6,ticks: Int=2200) {
        give(ItemStack(Items.DIAMOND,7))
        val result=TaskService.assign(server,npc,ExplorerTaskDefinition(dimension,start,radius=16,maxCells=cells,verticalRange=8,budget=TaskBudget(ticks)))
        check(result.status == NpcActionStatus.SUCCEEDED) { result.detail }
    }
    fun blockCell(x: Int,z: Int) {
        for (dx in -1..1) for (dz in -1..1) for (y in 3..8) helper.setBlock(BlockPos(24+x*4+dx,y,24+z*4+dz),Blocks.BEDROCK)
    }
    fun floodCell(x: Int,z: Int) {
        for (dx in -2..2) for (dz in -2..2) for (y in 1..4) {
            val edge=kotlin.math.abs(dx)==2 || kotlin.math.abs(dz)==2
            helper.setBlock(BlockPos(24+x*4+dx,y,24+z*4+dz),if (edge) Blocks.STONE else Blocks.WATER)
        }
    }
    fun interruptWithCombat(): LivingEntity {
        give(ItemStack(Items.IRON_SWORD))
        val enemy=checkNotNull(EntityType.HUSK.create(helper.level));enemy.isNoAi=true;enemy.setPersistenceRequired()
        enemy.moveTo(body.x,body.y,body.z+1.75)
        checkNotNull(enemy.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue=1.0
        enemy.health=4.0F;check(helper.level.addFreshEntity(enemy));extra.add(enemy)
        val result=TaskService.interrupt(server,body.uuid,AttackTaskDefinition(dimension,enemy.uuid,start,24.0,budget=TaskBudget(400)))
        check(result.status == NpcActionStatus.SUCCEEDED) { result.detail }
        return enemy
    }
    fun run(setup: ()->Unit,step: (TaskRecord)->Unit) {
        var started=false
        helper.onEachTick {
            if (ended || !started && !body.onGround()) return@onEachTick
            try {
                if (!started) { started=true;setup() }
                step(checkNotNull(TaskStore.forServer(server).get(body.uuid)))
            } catch (error: Exception) {
                com.mojang.logging.LogUtils.getLogger().error("Explorer failure tick={} npc={} task={} state={} position={}",helper.tick,body.uuid,
                    TaskStore.forServer(server).get(body.uuid)?.report(),TaskStore.forServer(server).get(body.uuid)?.primary?.explorer?.let(ExplorerTaskCodec::write),body.position(),error)
                close();throw error
            }
        }
    }
    fun released() {
        val snapshot=npc.snapshot()
        check(snapshot.navigation == null && snapshot.control == null && snapshot.itemUse == null && snapshot.fishing == null && snapshot.rangedAttack == null)
    }
    fun pass(record: TaskRecord) {
        check(TaskCodec.read(TaskCodec.write(record)).report() == record.report())
        released();check(BehaviorRuntimeService.assignedPacks(server,body.uuid).isEmpty())
        close();helper.succeed()
    }
    fun close() { if (ended) return;ended=true;body.discard();extra.forEach { it.discard() } }
}
