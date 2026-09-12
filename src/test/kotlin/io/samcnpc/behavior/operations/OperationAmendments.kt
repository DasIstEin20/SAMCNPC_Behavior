package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.NpcActionStatus

internal object OperationAmendments {
    fun apply(s: OperationScene, actor: OperationActor) {
        if(s.kind in OperationCourierCases.kinds) { OperationCourierCases.incident(s,actor);return }
        val change=when(s.kind) {
            OperationKind.AMEND_QUANTITY -> TaskChange.Quantity(8,QuantityChangeMode.ADD)
            OperationKind.AMEND_RECIPIENT -> TaskChange.Redirect(s.choices(24,-3))
            OperationKind.AMEND_RESOURCE -> {
                val old=s.record.primary.definition as MiningTaskDefinition
                TaskChange.Replace(old.copy(work=MiningWorkOrder(s.area(20,21),MiningMethod.EXPOSED,WorkResourceIds(listOf("minecraft:gold_ore"))),
                    outputs=WorkResourceIds(listOf("minecraft:raw_gold"))),ObjectiveChangeMode.NEW_OBJECTIVE)
            }
            else -> return
        }
        actor.approach(s)
        val record=s.record; val task=record.id; val frame=record.primary.id
        val remaining=record.primary.remainingTicks; val objective=record.amendments.objectiveId
        val result=TaskAmendments.automatic(s.server,s.npc,actor.player,change)
        check(result.status == NpcActionStatus.SUCCEEDED && result.detail.startsWith("APPLIED:")) { "${s.kind} amendment: ${result.detail}" }
        check(record.id == task && record.primary.id == frame && record.primary.remainingTicks == remaining && record.amendments.revision == 1)
        if(s.kind == OperationKind.AMEND_RESOURCE) {
            check(record.amendments.objectiveId != objective && record.amendments.objectives.single().objectiveId == objective)
            val old=record.amendments.objectives.single()
            check(old.resources["minecraft:raw_iron"]?.gathered == 1 && old.resources["minecraft:raw_iron"]?.retained == 6)
        } else check(record.amendments.objectiveId == objective)
    }
    fun replay(s: OperationScene,actor: OperationActor) {
        if(s.record.amendments.receipts.isEmpty()) return
        actor.approach(s)
        val before=TaskCodec.write(s.record); val stock=s.inventoryTag(); val world=s.worldFacts()
        for(receipt in s.record.amendments.receipts.toList()) {
            val result=TaskAmendments.request(s.server,s.npc,actor.player,receipt.request)
            check(result.status == NpcActionStatus.SUCCEEDED && result.detail.startsWith("APPLIED:")) { "${s.kind} stored request was not recognized after restart" }
        }
        check(TaskCodec.write(s.record) == before && s.inventoryTag() == stock && s.worldFacts() == world) { "${s.kind} replay repeated a world/task effect" }
    }
}
