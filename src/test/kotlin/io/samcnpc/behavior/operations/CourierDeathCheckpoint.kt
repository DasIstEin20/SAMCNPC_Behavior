package io.samcnpc.behavior.operations

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.AABB

/** A cancelled courier and its native death drops cross the same real JVM boundary as live tasks. */
internal class CourierDeathCheckpoint private constructor(val scene: OperationScene) {
    private var stage = 0
    private var ticks = 0
    private var stale: NpcFacade? = null
    var ready = false
        private set
    private val chunks = buildList {
        for (x in (scene.origin.x-3 shr 4)..(scene.origin.x+32 shr 4))
            for (z in (scene.origin.z-8 shr 4)..(scene.origin.z+12 shr 4)) add(ChunkPos(x,z))
    }
    init {
        // A dead identity has no activity ticket. The test loads this fixed inspection arena;
        // this is not evidence that production keeps arbitrary death sites loaded.
        for (chunk in chunks) scene.level.setChunkForced(chunk.x,chunk.z,true)
    }
    fun tickSave() {
        if (ready) return
        check(++ticks < 900) { "Courier death checkpoint timed out at stage $stage" }
        if (stage == 0) {
            if (!scene.loaded || !scene.body.onGround()) return
            OperationCourierCases.prepare(scene)
            stage = 1
        } else if (stage == 1) {
            if (!OperationCourierCases.checkpoint(scene)) return
            stale = scene.npc
            check(scene.carried("minecraft:diamond") == 62)
            check(scene.body.hurt(scene.body.damageSources().genericKill(),1000.0F))
            stage = 2
        } else if (scene.level.getEntity(scene.npcId) == null) {
            check(CoreNpcApi.service(scene.server).lifecycle(scene.npcId)?.state == NpcLifecycleState.DEAD)
            val rejected = checkNotNull(stale).stopControl()
            check(rejected.status == NpcActionStatus.REJECTED && rejected.code == NpcActionCode.NOT_FOUND)
            stale = null
            verifyNative()
            ready = true
        }
    }
    fun metadata(): CompoundTag {
        check(ready)
        verifyNative()
        val row = scene.metadata()
        row.put("task",TaskCodec.write(scene.record))
        row.put("world",scene.worldFacts())
        return row
    }
    fun loaded(): Boolean = chunks.all { scene.level.areEntitiesLoaded(it.toLong()) }
    fun verifyLoaded(expected: CompoundTag) {
        check(loaded())
        verifyNative()
        check(TaskCodec.write(scene.record) == expected.getCompound("task")) { "Dead courier task changed across restart" }
        check(scene.worldFacts() == expected.getCompound("world")) { "Native death cargo or containers changed across restart" }
        check(TaskService.resume(scene.server,scene.npcId).status == NpcActionStatus.REJECTED)
        check(scene.worldFacts() == expected.getCompound("world")) { "Rejected resume replayed a dead courier transfer" }
    }
    private fun verifyNative() {
        check(scene.level.getEntity(scene.npcId) == null)
        val service = CoreNpcApi.service(scene.server)
        check(service.find(scene.npcId)?.let(service::runtime) == null)
        check(BehaviorRuntimeService.diagnostic(scene.npcId) == null)
        val record = scene.record
        check(record.status == TaskStatus.CANCELLED && record.reason == TaskReason.NPC_REMOVED) { record.report() }
        val ledger = checkNotNull(record.primary.transport).ledger
        check(ledger.valid() && ledger.withdrawn == 5 && ledger.delivered == 2 && ledger.cargo == 3)
        check(scene.count(10,3,Items.DIAMOND) == 15 && scene.count(18,3,Items.DIAMOND) == 64)
        check(scene.count(24,-3,Items.DIAMOND) == 0)
        val drops = scene.level.getEntitiesOfClass(ItemEntity::class.java,AABB(scene.pos(-3,0,-8),scene.pos(33,12,13)))
        val diamonds = drops.sumOf { if (it.item.`is`(Items.DIAMOND)) it.item.count else 0 }
        val dirt = drops.sumOf { if (it.item.`is`(Items.DIRT)) it.item.count else 0 }
        check(diamonds == 62 && dirt == 35*64) { "Native courier death drops: diamonds=$diamonds dirt=$dirt" }
        check(15+64+diamonds == 20+62+59)
        // retained/cargo in the cancelled report are the last measured pre-death checkpoint;
        // only the actual world above proves where those items ended up after death.
    }
    fun release() { for (chunk in chunks) scene.level.setChunkForced(chunk.x,chunk.z,false) }
    companion object {
        fun create(server: MinecraftServer) = CourierDeathCheckpoint(OperationScene.create(server.overworld(),
            OperationKind.COURIER_REOPEN,BlockPos(800,80,700)))
        fun load(server: MinecraftServer,row: CompoundTag) = CourierDeathCheckpoint(OperationScene.load(server.overworld(),row))
    }
}
