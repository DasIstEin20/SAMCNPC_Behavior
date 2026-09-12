package io.samcnpc.behavior.operations

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraftforge.registries.ForgeRegistries
import java.util.UUID

internal enum class OperationKind {
    NAVIGATE, DELIVERY, WOOD, ATTACK, DEFEND, AREA_ATTACK, PATROL, TRANSPORT,
    INVENTORY, MINING, FOOD, FARM, PLANTING, WOOD_REPLANT,
    AMEND_QUANTITY, AMEND_RECIPIENT, AMEND_RESOURCE, COURIER_REDIRECT, COURIER_REOPEN, MACHINE, FISHING_WAIT, FISHING_COLLECT, EXPLORER_LEG, EXPLORER_RETURN,
}

/** Ordinary server fixture: no GameTest clock, entity restoration or fabricated action results. */
internal class OperationScene(val level: ServerLevel, val kind: OperationKind, val origin: BlockPos, val npcId: UUID) {
    val server get() = level.server
    val start get() = point(0, 0)
    var enemyId: UUID? = null
    var checked = false
    val loaded get() = level.getEntity(npcId) is LivingEntity && CoreNpcApi.service(server).find(npcId) != null
    val body get() = checkNotNull(level.getEntity(npcId) as? LivingEntity) { "$kind body $npcId is not loaded" }
    val npc: NpcFacade get() {
        val service = CoreNpcApi.service(server)
        return checkNotNull(service.find(npcId)?.let(service::runtime)) { "$kind Core facade is not loaded" }
    }
    val record get() = checkNotNull(TaskStore.forServer(server).get(npcId)) { "$kind task is missing" }
    fun block(x: Int, y: Int, z: Int) = NpcBlockPosition(origin.x+x, origin.y+y, origin.z+z)
    fun pos(x: Int, y: Int, z: Int) = origin.offset(x,y,z)
    fun point(x: Int, z: Int) = NpcPosition(origin.x+x+0.5, origin.y+1.0, origin.z+z+0.5)
    fun choices(x: Int, z: Int) = ContainerChoices(listOf(block(x,1,z)))
    fun area(x1: Int, x2: Int, height: Int = 1, z1: Int = 0, z2: Int = 0) =
        WorkArea(WorkBox(block(x1,1,z1),block(x2,height,z2)))
    fun chest(x: Int, z: Int): ChestBlockEntity = checkNotNull(level.getBlockEntity(pos(x,1,z)) as? ChestBlockEntity)
    fun makeChest(x: Int, z: Int): ChestBlockEntity {
        level.setBlock(pos(x,1,z),Blocks.CHEST.defaultBlockState(),3)
        return chest(x,z)
    }
    fun count(x: Int, z: Int, item: Item): Int {
        val chest = chest(x,z)
        return (0 until chest.containerSize).sumOf { slot -> val stack=chest.getItem(slot); if(stack.`is`(item)) stack.count else 0 }
    }
    fun carried(id: String) = TaskDelivery.inventoryCount(npc,id)
    fun give(item: Item, count: Int = 1) {
        var remaining = count
        while (remaining > 0) {
            val amount = minOf(remaining,item.maxStackSize)
            give(ItemStack(item,amount))
            remaining-=amount
        }
    }
    fun give(stack: ItemStack) {
        check(stack.count in 1..stack.maxStackSize)
        val drop=ItemEntity(level,body.x,body.y,body.z,stack.copy()); drop.setNoPickUpDelay()
        check(level.addFreshEntity(drop))
        check(npc.pickupItem(drop.uuid).status == NpcActionStatus.SUCCEEDED) { "$kind fixture stock insertion failed" }
    }
    fun assign(definition: TaskDefinition) {
        val result=TaskService.assign(server,npc,definition)
        check(result.status == NpcActionStatus.SUCCEEDED) { "$kind assignment: ${result.detail}" }
    }
    fun <T: Mob> enemy(type: EntityType<T>, x: Int, z: Int): T {
        val mob=checkNotNull(type.create(level))
        mob.moveTo(origin.x+x+0.5,origin.y+1.0,origin.z+z+0.5)
        mob.isNoAi=true; mob.setPersistenceRequired()
        checkNotNull(mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue=1.0
        checkNotNull(mob.getAttribute(Attributes.MAX_HEALTH)).baseValue=30.0
        mob.health=30.0F
        check(level.addFreshEntity(mob)); enemyId=mob.uuid
        return mob
    }
    fun requireCompleted() {
        check(record.status == TaskStatus.COMPLETED) { "$kind ${record.report()}" }
        check(TaskCodec.read(TaskCodec.write(record)).report() == record.report())
        requireReleased()
        check(BehaviorRuntimeService.assignedPacks(server,npcId).isEmpty()) { "$kind retained its task pack" }
    }
    fun requireReleased() {
        val state=npc.snapshot()
        check(state.navigation == null && state.control == null && state.blockBreak == null && state.itemUse == null && state.rangedAttack == null && state.fishing == null) { "$kind retained Core control" }
    }
    fun requireReturned(arrival: Double = 0.75) = check(TaskNavigator.distanceSquared(npc.snapshot().position,start) <= arrival*arrival) { "$kind did not physically return" }
    fun close() { body.discard(); enemyId?.let { level.getEntity(it)?.discard() } }
    fun metadata(): CompoundTag {
        val tag=CompoundTag()
        tag.putString("kind",kind.name); tag.putLong("origin",origin.asLong()); tag.putUUID("npc",npcId)
        enemyId?.let { tag.putUUID("enemy",it) }
        return tag
    }
    fun inventoryTag(): CompoundTag {
        val saved=body.saveWithoutId(CompoundTag())
        val tag=CompoundTag()
        for(key in listOf("Items","HandItems","ArmorItems")) saved.get(key)?.let { tag.put(key,it.copy()) }
        tag.putInt("selectedHotbar",npc.snapshot().selectedHotbarSlot)
        return tag
    }
    fun worldFacts(): CompoundTag {
        val tag=CompoundTag(); val blocks=ListTag(); val containers=ListTag()
        // The small arena is fixed in advance; comparisons never repair the actual world.
        val minimumY=if (kind in OperationFishingCase.kinds) -2 else 0
        for(x in -2..30) for(z in -7..10) for(y in minimumY..9) {
            val at=pos(x,y,z); val state=level.getBlockState(at)
            if(state.isAir) continue
            val row=CompoundTag(); row.putLong("pos",at.asLong()); row.put("state",NbtUtils.writeBlockState(state)); blocks.add(row)
            val chest=level.getBlockEntity(at) as? ChestBlockEntity ?: continue
            val items=ListTag()
            for(slot in 0 until chest.containerSize) {
                val stack=chest.getItem(slot)
                if(!stack.isEmpty) { val item=stack.save(CompoundTag()); item.putInt("slot",slot); items.add(item) }
            }
            val container=CompoundTag(); container.putLong("pos",at.asLong()); container.put("items",items); containers.add(container)
        }
        tag.put("blocks",blocks); tag.put("containers",containers)
        val drops=ListTag()
        for(drop in level.getEntitiesOfClass(ItemEntity::class.java,AABB(pos(-3,0,-8),pos(33,12,13))).sortedBy { it.uuid }) {
            val row=drop.item.save(CompoundTag()); row.putUUID("entity",drop.uuid); drops.add(row)
        }
        tag.put("drops",drops)
        enemyId?.let { id -> val enemy=level.getEntity(id) as? LivingEntity
            checkNotNull(enemy) { "$kind enemy disappeared before checkpoint" }
            tag.putFloat("enemyHealth",enemy.health)
        }
        return tag
    }
    companion object {
        fun create(level: ServerLevel,kind: OperationKind,origin: BlockPos,spawnX: Double=0.5,spawnZ: Double=0.5,yaw: Float=-90.0F): OperationScene {
            // Clear plants before replacing their soil, which would otherwise release old sapling/seed drops.
            for(x in -3..32) for(z in -8..12) for(y in 10 downTo 0)
                level.setBlock(origin.offset(x,y,z),(if(y == 0) Blocks.STONE else Blocks.AIR).defaultBlockState(),3)
            val type=checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core","npc")))
            val body=checkNotNull(type.create(level)) as LivingEntity
            body.moveTo(origin.x+spawnX,origin.y+1.0,origin.z+spawnZ,yaw,0.0F)
            body.customName=net.minecraft.network.chat.Component.literal("O4-${kind.name}")
            checkNotNull(body.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).baseValue=1.0
            check(level.addFreshEntity(body))
            return OperationScene(level,kind,origin,body.uuid)
        }
        fun load(level: ServerLevel,tag: CompoundTag): OperationScene {
            val scene=OperationScene(level,OperationKind.valueOf(tag.getString("kind")),BlockPos.of(tag.getLong("origin")),tag.getUUID("npc"))
            if(tag.hasUUID("enemy")) scene.enemyId=tag.getUUID("enemy")
            return scene
        }
    }
}
