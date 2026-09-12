package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.NpcActionStatus
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** Shared restart campaign: finite partial cargo, outside edits, full destination and route correction. */
internal object OperationCourierCases {
    val kinds=setOf(OperationKind.COURIER_REDIRECT,OperationKind.COURIER_REOPEN)
    fun prepare(s:OperationScene) {
        s.makeChest(10,3).setItem(0,ItemStack(Items.DIAMOND,20))
        val destination=s.makeChest(18,3)
        destination.setItem(0,ItemStack(Items.DIAMOND,62))
        for(slot in 1 until destination.containerSize) destination.setItem(slot,ItemStack(Items.DIRT,64))
        s.makeChest(24,-3)
        s.give(Items.DIAMOND,59);s.give(Items.DIRT,35*64)
        s.assign(TransportTaskDefinition(s.npc.snapshot().dimensionId,s.choices(10,3),s.choices(18,3),"minecraft:diamond",12,s.start,
            keepAtLeast=59,sourceKeepAtLeast=5,returnTo=s.start,budget=TaskBudget(5000)))
    }
    fun checkpoint(s:OperationScene):Boolean {
        val state=s.record.primary.transport ?: return false
        return state.ledger.withdrawn==5 && state.ledger.delivered==2 && s.record.status==TaskStatus.WAITING && s.record.reason==TaskReason.STORAGE_FULL
    }
    fun incident(s:OperationScene,actor:OperationActor) {
        check(checkpoint(s))
        val record=s.record;val id=record.id;val primary=record.primary.id;val remaining=record.primary.remainingTicks
        val source=s.chest(10,3);check(source.getItem(0).count==15)
        source.removeItem(0,3);source.setItem(1,ItemStack(Items.EMERALD,7));source.setChanged()
        val previous=s.chest(18,3)
        check(previous.getItem(0).count==64)
        previous.setItem(1,ItemStack(Items.GOLD_INGOT,32))
        if(s.kind==OperationKind.COURIER_REDIRECT) {
            actor.approach(s)
            val change=TaskAmendments.automatic(s.server,s.npc,actor.player,TaskChange.Redirect(s.choices(24,-3)))
            check(change.status==NpcActionStatus.SUCCEEDED && change.detail.startsWith("APPLIED:")) { change.detail }
        } else previous.setItem(2,ItemStack.EMPTY)
        previous.setChanged()
        check(s.record.id==id && s.record.primary.id==primary && s.record.primary.remainingTicks==remaining)
        check(s.record.primary.transport?.ledger?.delivered==2 && s.carried("minecraft:diamond")==62)
        // The actor explicitly removes 3 diamonds and changes unrelated cargo. Those are
        // external inputs, not NPC delivery/consumption, and remain part of the saved world.
    }
    fun verify(s:OperationScene) {
        s.requireCompleted();s.requireReturned()
        val redirected=s.kind==OperationKind.COURIER_REDIRECT
        check(s.count(10,3,Items.DIAMOND)==5 && s.count(10,3,Items.EMERALD)==7)
        check(s.count(18,3,Items.DIAMOND)==if(redirected) 64 else 74)
        check(s.count(24,-3,Items.DIAMOND)==if(redirected) 10 else 0)
        check(s.count(18,3,Items.GOLD_INGOT)==32 && s.carried("minecraft:diamond")==59 && s.carried("minecraft:dirt")==35*64)
        val ledger=checkNotNull(s.record.primary.transport).ledger
        check(ledger.valid() && ledger.withdrawn==12 && ledger.delivered==12 && ledger.transferCount>=6)
        check(s.record.amendments.revision==if(redirected) 1 else 0)
        check(5+s.count(18,3,Items.DIAMOND)+s.count(24,-3,Items.DIAMOND)+s.carried("minecraft:diamond")==20+62+59-3)
    }
}
