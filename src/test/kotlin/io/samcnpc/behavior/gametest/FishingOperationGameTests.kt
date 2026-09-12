package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.Container
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.function.Consumer

@GameTestHolder("samcnpc_fishing_zoo")
@PrefixGameTestTemplate(false)
object FishingOperationGameTests {
    private enum class Incident { NORMAL, PAUSE_COMBAT, CANCEL, DRY, ROD_BREAK, FULL, FENCE, HOOK_GONE, DEADLINE, COLLECT_COMBAT }
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=2600,batch="fishing_task_normal")
    fun twoRealCatchesAreCollectedAndReturnedWithProtectedStock(h: GameTestHelper) = fishing(h,Incident.NORMAL)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=2600,batch="fishing_task_pause")
    fun pausedWaitingHookIsCancelledBeforeCombatAndOriginalTaskResumes(h: GameTestHelper) = fishing(h,Incident.PAUSE_COMBAT)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=800,batch="fishing_task_cancel")
    fun cancelledTaskCannotLeaveASecondPayoutOrLiveHook(h: GameTestHelper) = fishing(h,Incident.CANCEL)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=1000,batch="fishing_task_dry")
    fun drainedPondFailsWithinOriginalRetryBudget(h: GameTestHelper) = fishing(h,Incident.DRY)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=1800,batch="fishing_task_rod")
    fun lastDurabilityPreservesTheCatchAndRequiresAPhysicalReplacementRod(h: GameTestHelper) = fishing(h,Incident.ROD_BREAK)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=1800,batch="fishing_task_full")
    fun fullInventoryWaitsForActualExternalStorageBeforeCasting(h: GameTestHelper) = fishing(h,Incident.FULL)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=1800,batch="fishing_task_fence")
    fun saveInsideNativePayoutPreservesUnconfirmedRecordWithoutReplay(h: GameTestHelper) = fishing(h,Incident.FENCE)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=1800,batch="fishing_task_hook")
    fun externallyRemovedHookStartsABoundedNewCastWithTheSameTask(h: GameTestHelper) = fishing(h,Incident.HOOK_GONE)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=300,batch="fishing_task_deadline")
    fun originalDeadlineCancelsLongWaitingWithoutInventingACatch(h: GameTestHelper) = fishing(h,Incident.DEADLINE)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=2600,batch="fishing_task_collect")
    fun collectionPauseAndCombatRetainActualDropObligation(h: GameTestHelper) = fishing(h,Incident.COLLECT_COMBAT)

    private fun fishing(h: GameTestHelper, incident: Incident) {
        val arena=FishingGameTestArena(h)
        val quota=if (incident in setOf(Incident.NORMAL,Incident.ROD_BREAK)) 2 else 1
        val duration=if (incident == Incident.DEADLINE) 80 else 2400
        var original: UUID?=null
        var remaining=duration
        var intervened=false
        var pausedTicks=0
        var frozenRemaining=0
        var frozenCollect=0
        var frozenCatches=0
        var initialHook: UUID?=null
        var observedCasts=linkedSetOf<UUID>()
        var enemy: LivingEntity?=null
        var combatSubmitted=false
        var fenceSnapshots=0
        var terminalTicks=0
        var stableTerminal: TaskReport?=null
        var freed=false
        val storageAt=h.absolutePos(BlockPos(3,3,8))
        val listener=Consumer<NpcFishingLootCheckEvent> { event ->
            if (event.npcUuid == arena.body.uuid && incident == Incident.FENCE) {
                val record=checkNotNull(TaskStore.forServer(arena.server).get(arena.body.uuid))
                check(checkNotNull(record.primary.fishing).pendingReel)
                check(arena.npc.fishingState()?.phase == NpcFishingPhase.REELING)
                val saved=TaskStore.forServer(arena.server).save(CompoundTag())
                val isolated=TaskStore.load(saved.copy())
                check(isolated.get(arena.body.uuid) == null)
                val preserved=isolated.save(CompoundTag())
                check(preserved.allKeys == saved.allKeys && preserved.getInt("version") == saved.getInt("version"))
                // TaskStore writes valid records first, then rejected originals. Compare every
                // complete NBT record independently of that documented file ordering.
                val before=saved.getList("tasks",10).map { it as CompoundTag }.sortedBy { it.getUUID("npcUuid").toString() }
                val after=preserved.getList("tasks",10).map { it as CompoundTag }.sortedBy { it.getUUID("npcUuid").toString() }
                check(before == after) { "unconfirmed fishing checkpoint or a sibling record was rewritten" }
                fenceSnapshots++
            }
        }
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL,false,NpcFishingLootCheckEvent::class.java,listener)
        arena.cleanup={ MinecraftForge.EVENT_BUS.unregister(listener) }
        arena.run(setup={
            arena.rod(lastUse=incident == Incident.ROD_BREAK,lure=incident != Incident.DEADLINE)
            arena.give(ItemStack(Items.DIAMOND,7))
            if (incident == Incident.PAUSE_COMBAT || incident == Incident.COLLECT_COMBAT) arena.give(ItemStack(Items.IRON_SWORD))
            if (incident == Incident.FULL) {
                repeat(34) { arena.give(ItemStack(Items.DIRT,64)) }
                h.level.setBlock(storageAt,Blocks.CHEST.defaultBlockState(),3)
            }
            arena.assign(quota,duration,if (incident == Incident.DEADLINE) 40 else 240)
        }) { record ->
            if (original == null) original=record.id
            check(record.id == original && record.primary.remainingTicks <= remaining)
            remaining=record.primary.remainingTicks
            val state=checkNotNull(record.primary.fishing)
            val hook=arena.npc.fishingState()
            if (hook != null) {
                check(arena.hooks().size == 1)
                observedCasts.add(hook.actionId)
                if (initialHook == null) initialHook=hook.actionId
            }
            check(TaskDelivery.inventoryCount(arena.npc,"minecraft:diamond") == 7)
            if (!record.status.terminal) {
                val waiting=hook != null && hook.phase in setOf(NpcFishingPhase.WAITING,NpcFishingPhase.APPROACHING)
                when (incident) {
                    Incident.PAUSE_COMBAT, Incident.COLLECT_COMBAT -> {
                        val ready=if (incident == Incident.COLLECT_COMBAT) state.phase == FishingPhase.COLLECT else waiting
                        if (!intervened && ready) {
                            check(TaskService.pause(arena.server,arena.body.uuid).status == NpcActionStatus.SUCCEEDED)
                            check(arena.npc.fishingState() == null)
                            frozenRemaining=record.primary.remainingTicks
                            frozenCollect=state.collectTicks;frozenCatches=state.caught
                            intervened=true
                        }
                        if (record.status == TaskStatus.PAUSED) {
                            check(state.collectTicks == frozenCollect && state.caught == frozenCatches)
                            check(record.primary.remainingTicks == frozenRemaining)
                            if (++pausedTicks == 30) check(TaskService.resume(arena.server,arena.body.uuid).status == NpcActionStatus.SUCCEEDED)
                        } else if (intervened && !combatSubmitted && (incident == Incident.COLLECT_COMBAT || waiting)) {
                            val target=arena.enemy();enemy=target;arena.interrupt(target);combatSubmitted=true
                            check(arena.npc.fishingState() == null)
                        }
                        if (record.frames.size > 1) check(state.collectTicks == frozenCollect)
                    }
                    Incident.CANCEL -> if (!intervened && waiting) {
                        check(TaskService.cancel(arena.server,arena.body.uuid).status == NpcActionStatus.SUCCEEDED);intervened=true
                    }
                    Incident.DRY -> if (!intervened && waiting) { arena.fill(Blocks.AIR);intervened=true }
                    Incident.HOOK_GONE -> if (!intervened && waiting) {
                        checkNotNull(h.level.getEntity(checkNotNull(hook).hookUuid)).discard();intervened=true
                    }
                    Incident.FULL -> if (!freed && h.tick >= 30) {
                        check(state.casts == 0 && hook == null)
                        val dirt=arena.npc.inventoryContents().first { it.stack.itemId == "minecraft:dirt" }
                        val result=arena.npc.transferToContainer(dirt.slot,NpcContainerTransferRequest(
                            NpcContainerEndpoint(arena.dimension,NpcBlockPosition(storageAt.x,storageAt.y,storageAt.z)),0,"minecraft:dirt",64))
                        check(!result.uncertain && result.movedCount == 64);freed=true
                    }
                    else -> Unit
                }
            }
            if (record.status.terminal) {
                if (stableTerminal == null) stableTerminal=record.report()
                check(record.report() == stableTerminal)
                arena.released()
                if (++terminalTicks == 70) {
                    when (incident) {
                        Incident.CANCEL -> check(intervened && record.status == TaskStatus.CANCELLED && state.caught == 0)
                        Incident.DRY -> check(intervened && record.status == TaskStatus.FAILED && state.caught == 0 && record.primary.failures <= 3)
                        Incident.DEADLINE -> check(record.status == TaskStatus.FAILED && record.reason == TaskReason.TIME_LIMIT && state.caught == 0 && observedCasts.size == 1)
                        Incident.ROD_BREAK -> {
                            val replacements=state.spawned[FishingTaskDefinition.ROD] ?: 0
                            val rods=checkNotNull(state.resources.entries[FishingTaskDefinition.ROD])
                            check(rods.initial == 1 && rods.consumed+rods.lost >= 1)
                            if (replacements == 0) {
                                check(record.status == TaskStatus.FAILED && state.caught == 1 && state.collectedCatches == 1)
                                check(arena.npc.inventoryContents().none { it.stack.itemId == FishingTaskDefinition.ROD })
                            } else {
                                // Real vanilla loot can itself be a usable rod. Continuing then
                                // requires that actual pickup; no replacement is fabricated.
                                check(rods.gathered >= replacements)
                                check(record.status == TaskStatus.COMPLETED && state.caught == quota && state.collectedCatches == quota)
                            }
                        }
                        else -> {
                            check(record.status == TaskStatus.COMPLETED && record.reason == TaskReason.FISHING_FINISHED) { record.report() }
                            check(state.caught == quota && state.collectedCatches == quota)
                            check(TaskNavigator.distanceSquared(arena.npc.snapshot().position,arena.start) <= 0.75*0.75)
                            val rod=arena.npc.inventoryContents().first { it.slot == 0 && it.stack.itemId == FishingTaskDefinition.ROD }.stack
                            check(rod.damage == quota)
                        }
                    }
                    check(state.spawned.all { (id,count) -> (state.resources.entries[id]?.gathered ?: 0) >= count })
                    if (incident == Incident.PAUSE_COMBAT || incident == Incident.COLLECT_COMBAT) {
                        check(pausedTicks == 30 && combatSubmitted && enemy?.isAlive == false && record.completedInterruptions == 1)
                        check(record.primary.remainingTicks < frozenRemaining)
                        if (incident == Incident.PAUSE_COMBAT) check(observedCasts.size == 3 && state.casts == 3)
                    }
                    if (incident == Incident.HOOK_GONE) check(observedCasts.size == 2 && state.casts == 2)
                    if (incident == Incident.FENCE) check(fenceSnapshots == 1)
                    if (incident == Incident.FULL) {
                        check(freed)
                        val chest=checkNotNull(h.level.getBlockEntity(storageAt) as? Container)
                        check(chest.getItem(0).`is`(Items.DIRT) && chest.getItem(0).count == 64)
                        check(TaskDelivery.inventoryCount(arena.npc,"minecraft:dirt") == 33*64)
                    }
                    com.mojang.logging.LogUtils.getLogger().info("FISHING_TASK_NATIVE incident={} task={} casts={} caught={} collected={} receipts={} reason={}",
                        incident,record.id,state.casts,state.caught,state.collectedCatches,state.spawned,record.reason)
                    arena.pass(record)
                }
            }
        }
    }
}
