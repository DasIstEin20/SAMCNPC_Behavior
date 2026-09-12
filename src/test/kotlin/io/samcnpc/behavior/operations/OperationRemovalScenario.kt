package io.samcnpc.behavior.operations

import io.samcnpc.behavior.kernel.work.HarvestWorkClaims
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.portal.PortalInfo
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.util.ITeleporter
import java.util.UUID
import java.util.function.Function

internal enum class OperationRemovalKind { DEATH, DISMISS, DIMENSION }

internal class OperationRemovalScenario(server: MinecraftServer, val kind: OperationRemovalKind) {
    val scene = OperationScene.create(server.overworld(),
        if (kind == OperationRemovalKind.DEATH) OperationKind.MINING else OperationKind.NAVIGATE,
        BlockPos(1100 + kind.ordinal * 64, 80, 1000))
    private var stage = 0
    private var ticks = 0
    private var taskId: UUID? = null
    private var stale: NpcFacade? = null
    private var transferred: LivingEntity? = null
    private var heldClaim = false
    var complete = false
        private set

    fun tick() {
        if (complete) return
        check(++ticks < 900) { "Removal scenario timed out: $kind stage=$stage" }
        val service = CoreNpcApi.service(scene.server)
        if (stage == 0) {
            if (!scene.loaded || !scene.body.onGround()) return
            OperationCases.prepare(scene)
            taskId = scene.record.id
            stage = 1
            return
        }
        if (stage == 1) {
            val snapshot = scene.npc.snapshot()
            if (kind == OperationRemovalKind.DEATH) {
                if (snapshot.blockBreak == null) return
                heldClaim = !outsiderCanCollect()
                check(heldClaim) { "Death fixture never held a real harvest reservation" }
            } else if (snapshot.navigation == null || TaskNavigator.distanceSquared(snapshot.position, scene.start) < 1.0) return
            stale = scene.npc
            when (kind) {
                OperationRemovalKind.DEATH -> check(scene.body.hurt(scene.body.damageSources().genericKill(), 1000.0F))
                OperationRemovalKind.DISMISS -> check(service.dismiss(checkNotNull(service.find(scene.npcId)),
                    NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
                OperationRemovalKind.DIMENSION -> {
                    val destination = checkNotNull(scene.server.getLevel(Level.NETHER))
                    val target = BlockPos(1300, 120, 1300)
                    for (x in -3..3) for (z in -3..3) for (y in 0..4)
                        destination.setBlock(target.offset(x, y, z),
                            (if (y == 0) Blocks.STONE else Blocks.AIR).defaultBlockState(), 3)
                    transferred = scene.body.changeDimension(destination, object : ITeleporter {
                        override fun getPortalInfo(entity: Entity, level: ServerLevel,
                            fallback: Function<ServerLevel, PortalInfo>): PortalInfo =
                            PortalInfo(Vec3(target.x + 0.5, target.y + 1.0, target.z + 0.5), Vec3.ZERO, 0.0F, 0.0F)
                    }) as? LivingEntity
                    check(transferred?.uuid == scene.npcId) { "Native dimension recreation lost identity" }
                }
            }
            stage = 2
            return
        }
        val record = scene.record
        if (!record.status.terminal) return
        val expectedStatus = if (kind == OperationRemovalKind.DIMENSION) TaskStatus.FAILED else TaskStatus.CANCELLED
        val expectedReason = if (kind == OperationRemovalKind.DIMENSION) TaskReason.DIMENSION_CHANGED else TaskReason.NPC_REMOVED
        check(record.id == taskId && record.status == expectedStatus && record.reason == expectedReason) { record.report() }
        val oldAction = checkNotNull(stale).stopControl()
        check(oldAction.status == NpcActionStatus.REJECTED && oldAction.code == NpcActionCode.NOT_FOUND)
        if (kind == OperationRemovalKind.DIMENSION) {
            val fresh = service.find(scene.npcId)?.let(service::runtime) ?: return
            val snapshot = fresh.snapshot()
            if (!snapshot.onGround) return
            check(snapshot.dimensionId == "minecraft:the_nether")
            check(snapshot.navigation == null && snapshot.control == null && snapshot.blockBreak == null)
            check(service.lifecycle(scene.npcId)?.state == NpcLifecycleState.LOADED)
            checkNotNull(transferred).discard()
            transferred = null
        } else {
            if (service.find(scene.npcId)?.let(service::runtime) != null) return
            check(BehaviorRuntimeService.diagnostic(scene.npcId) == null)
            check(service.lifecycle(scene.npcId)?.state ==
                if (kind == OperationRemovalKind.DEATH) NpcLifecycleState.DEAD else NpcLifecycleState.DISMISSED)
            if (kind == OperationRemovalKind.DEATH) {
                check(heldClaim && outsiderCanCollect()) { "Death retained a harvest reservation" }
                check(record.primary.mining?.selection?.removed?.isEmpty() == true)
                check((12..13).all { scene.level.getBlockState(scene.pos(it, 1, 0)).`is`(Blocks.IRON_ORE) })
                check(scene.count(0, 7, net.minecraft.world.item.Items.RAW_IRON) == 0)
            }
        }
        stale = null
        complete = true
    }

    private fun outsiderCanCollect(): Boolean {
        val point = scene.point(12, 0)
        val outsider = UUID(0, 1)
        val permitted = HarvestWorkClaims.kernel.incidentalCollectionFilter(outsider, outsider,
            scene.level.dimension().location().toString(), point, 2.0, scene.level.gameTime)
        return permitted(point)
    }
}
