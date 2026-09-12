package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** Explicit target filter, exact attack interruption and confirmed outcome; deaths are never food. */
internal object FoodHunting {
    fun select(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FoodTaskDefinition,s: FoodTaskState): NpcActionResult {
        val work=d.work as FoodWorkOrder.Hunt
        if (s.hunts.size >= work.limit) return TaskFood.stop(e,npc,s,FoodProblem.HUNT_LIMIT)
        if (!BehaviorPlanning.admit(world,65,PlanningKind.GENERAL)) return NpcActionResult.running("explicit hunting observation queued")
        val snapshot=npc.snapshot()
        val observed=world.queryEntities(NpcEntityQuery(snapshot.position,32.0,64,typeFilter=work.targets))
        val target=observed.filter { it.alive && !it.isPlayer && it.combat?.permitted == true && it.combat?.allied == false && it.uuid !in s.hunts && d.inWork(it.position) }
            .minWithOrNull(compareBy<NpcEntityObservation> { TaskNavigator.distanceSquared(snapshot.position,it.position) }.thenBy { it.uuid })
            ?: return TaskFood.stop(e,npc,s,if (observed.size == 64) FoodProblem.OBSERVATION_LIMIT else null)
        val duration=minOf(600,record.primary.remainingTicks)
        if (duration < 20) return TaskFood.stop(e,npc,s,FoodProblem.HUNT_ENDED)
        val attack=AttackTaskDefinition(d.dimensionId,target.uuid,snapshot.position,leash=32.0,allowPlayers=false,budget=TaskBudget(ticks=duration))
        val problem=record.interrupt(attack)
        if (problem != null) return TaskFood.stop(e,npc,s,FoodProblem.HUNT_LIMIT)
        s.huntTarget=target.uuid; s.huntFrame=record.active.id; s.collectionOrigin=target.position; s.phase=FoodPhase.HUNT
        TaskInventory.resetRoute(e,npc)
        return NpcActionResult.running("explicit hunt delegated to bounded exact-target attack: ${target.uuid}")
    }
    /** Checked before the combat action, including target movement while the food frame is suspended. */
    fun observe(record: TaskRecord,world: NpcWorldView) {
        val d=record.primary.definition as? FoodTaskDefinition ?: return
        val work=d.work as? FoodWorkOrder.Hunt ?: return
        val s=checkNotNull(record.primary.food)
        if (record.status != TaskStatus.RUNNING || s.huntFrame != record.active.id) return
        val targetId=s.huntTarget ?: return
        val target=world.observeEntity(targetId,work.targets)
        if (target != null && target.alive && (!d.inWork(target.position) || target.isPlayer || target.combat?.permitted != true || target.combat?.allied == true)) {
            record.endCombat(TaskStatus.CANCELLED,TaskReason.LEASH_REACHED,0,"hunt target left its explicit area or permissions")
        } else if (target != null && d.inWork(target.position)) s.collectionOrigin=target.position
    }
    fun resume(record: TaskRecord,e: TaskExecution,npc: NpcFacade,d: FoodTaskDefinition,s: FoodTaskState): NpcActionResult {
        val target=checkNotNull(s.huntTarget)
        val outcome=record.lastCombat
        if (outcome == null || outcome.targetUuid != target) return FoodAccounting.mismatch(record,"resumed hunt lacks its exact combat outcome")
        s.hunts[target]=outcome.confirmedKills
        s.huntTarget=null; s.huntFrame=null
        if (outcome.confirmedKills != 1) return TaskFood.stop(e,npc,s,FoodProblem.HUNT_ENDED)
        if (s.collectionOrigin == null || !d.inWork(checkNotNull(s.collectionOrigin))) return TaskFood.stop(e,npc,s,FoodProblem.HUNT_ENDED)
        s.phase=FoodPhase.COLLECT; s.collectionTicks=FoodTaskState.COLLECTION_TICKS
        TaskInventory.resetRoute(e,npc)
        return NpcActionResult.running("exact hunt confirmed; collecting real edible drops before considering another target")
    }
}
