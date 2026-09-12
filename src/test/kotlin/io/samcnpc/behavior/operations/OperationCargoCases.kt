package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal object OperationCargoCases {
    fun prepare(s: OperationScene) {
        val dim=s.npc.snapshot().dimensionId
        val budget=TaskBudget(2400)
        when(s.kind) {
            OperationKind.NAVIGATE -> s.assign(NavigateTaskDefinition(dim,s.point(26,0),budget=budget))
            OperationKind.DELIVERY, OperationKind.AMEND_QUANTITY, OperationKind.AMEND_RECIPIENT -> {
                s.makeChest(18,3); s.makeChest(24,-3)
                val quantityAmend=s.kind == OperationKind.AMEND_QUANTITY
                s.give(Items.DIAMOND,if(quantityAmend) 90 else 80)
                s.assign(DeliveryTaskDefinition(dim,s.block(18,1,3),"minecraft:diamond",72,if(quantityAmend) 10 else 8,budget))
            }
            OperationKind.TRANSPORT -> {
                s.makeChest(10,3).setItem(0,ItemStack(Items.DIAMOND,20)); s.makeChest(24,-3)
                s.give(Items.DIAMOND,5)
                s.assign(TransportTaskDefinition(dim,s.choices(10,3),s.choices(24,-3),"minecraft:diamond",12,s.start,
                    keepAtLeast=5,sourceKeepAtLeast=8,returnTo=s.start,budget=budget))
            }
            OperationKind.INVENTORY -> {
                s.makeChest(18,3).setItem(0,ItemStack(Items.DIAMOND,20)); s.give(Items.DIAMOND,3)
                s.assign(InventoryTaskDefinition(dim,SupplyStock(listOf(StockNeed("minecraft:diamond",10,10,13)),s.choices(18,3)),
                    s.start,s.start,travelRadius=64.0,workTicks=1200,budget=budget))
            }
            OperationKind.FOOD -> {
                s.makeChest(12,3).setItem(0,ItemStack(Items.BREAD,20)); s.makeChest(24,-3); s.give(Items.BREAD,2)
                s.assign(FoodTaskDefinition(dim,FoodWorkOrder.Stored(s.choices(12,3),8),WorkResourceIds(listOf("minecraft:bread")),
                    s.choices(24,-3),12,2,s.start,returnTo=s.start,budget=budget))
            }
            else -> error("${s.kind} is not a cargo case")
        }
    }
    fun checkpoint(s: OperationScene): Boolean = when(s.kind) {
        OperationKind.NAVIGATE -> s.npc.snapshot().position.x > s.origin.x+8 && s.npc.snapshot().navigation != null
        OperationKind.DELIVERY, OperationKind.AMEND_QUANTITY, OperationKind.AMEND_RECIPIENT -> s.record.primary.resources?.delivered == 64
        OperationKind.TRANSPORT -> s.record.primary.transport?.ledger?.withdrawn == 12
        OperationKind.INVENTORY -> s.record.primary.inventory?.phase == InventoryWorkPhase.RETURN
        OperationKind.FOOD -> s.record.primary.food?.withdrawals?.isNotEmpty() == true
        else -> false
    }
    fun verify(s: OperationScene) {
        s.requireCompleted()
        when(s.kind) {
            OperationKind.NAVIGATE -> check(TaskNavigator.distanceSquared(s.npc.snapshot().position,s.point(26,0)) <= 0.75*0.75)
            OperationKind.DELIVERY -> check(s.count(18,3,Items.DIAMOND) == 72 && s.carried("minecraft:diamond") == 8)
            OperationKind.AMEND_QUANTITY -> {
                check(s.count(18,3,Items.DIAMOND) == 80 && s.carried("minecraft:diamond") == 10)
                check(s.record.amendments.revision == 1 && s.record.amendments.objectives.isEmpty())
            }
            OperationKind.AMEND_RECIPIENT -> {
                check(s.count(18,3,Items.DIAMOND) == 64 && s.count(24,-3,Items.DIAMOND) == 8 && s.carried("minecraft:diamond") == 8)
                check(s.record.amendments.revision == 1 && s.record.amendments.objectives.isEmpty())
            }
            OperationKind.TRANSPORT -> {
                check(s.count(10,3,Items.DIAMOND) == 8 && s.count(24,-3,Items.DIAMOND) == 12 && s.carried("minecraft:diamond") == 5)
                val ledger=checkNotNull(s.record.primary.transport).ledger
                check(ledger.valid() && ledger.withdrawn == 12 && ledger.delivered == 12 && ledger.transferCount == 2)
                s.requireReturned()
            }
            OperationKind.INVENTORY -> {
                check(s.count(18,3,Items.DIAMOND) == 13 && s.carried("minecraft:diamond") == 10)
                check(s.record.primary.inventory?.resources?.entries?.get("minecraft:diamond")?.supplied == 7)
                s.requireReturned()
            }
            OperationKind.FOOD -> {
                check(s.count(12,3,Items.BREAD) == 8 && s.count(24,-3,Items.BREAD) == 12 && s.carried("minecraft:bread") == 2)
                val food=checkNotNull(s.record.primary.food)
                check(food.resources.physical.entries.values.all { it.valid() } && food.resources.delivered("minecraft:bread") == 12)
                s.requireReturned()
            }
            else -> error("${s.kind} is not a cargo result")
        }
    }
}
