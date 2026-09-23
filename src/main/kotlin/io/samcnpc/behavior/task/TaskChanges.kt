package io.samcnpc.behavior.task

/** Bounded typed edits are resolved only after replay/revision validation. */
internal object TaskChanges {
    fun validationProblem(change: TaskChange): String? = when (change) {
        is TaskChange.Quantity -> if (change.amount !in 1..2304) "quantity must be 1..2304 in the operation's declared counting basis" else null
        is TaskChange.Redirect -> change.recipients.validationProblem()
        is TaskChange.Sources -> change.sources?.validationProblem()
        is TaskChange.Replace -> change.definition.validationProblem()
        is TaskChange.ExtendTime -> if (change.ticks !in 1..72000) "time extension must be 1..72000 ticks" else null
        is TaskChange.Tactics -> change.tactics.validationProblem()
        is TaskChange.Reaction -> change.policy.validationProblem()
        is TaskChange.Logistics -> change.policy.validationProblem()
    }
    fun proposed(old: TaskDefinition, change: TaskChange): TaskDefinition = when (change) {
        is TaskChange.Replace -> change.definition
        is TaskChange.Reaction, is TaskChange.Logistics -> old
        is TaskChange.Quantity -> {
            val quantity = change.amount + if (change.mode == QuantityChangeMode.ADD) quantity(old) else 0
            when (old) {
                is DeliveryTaskDefinition -> old.copy(quantity = quantity)
                is TransportTaskDefinition -> old.copy(quantity = quantity)
                is PlantingTaskDefinition -> old.copy(quantity = quantity)
                is FarmTaskDefinition -> old.copy(quantity = quantity)
                is FoodTaskDefinition -> old.copy(quantity = quantity)
                is MiningTaskDefinition -> old.copy(quantity = quantity)
                is LumberjackTaskDefinition -> old.copy(quantity = quantity)
                is AreaAttackTaskDefinition -> old.copy(quota = quantity)
                else -> throw IllegalArgumentException("quantity: this operation has no amendable item/defeat quota")
            }
        }
        is TaskChange.Redirect -> when (old) {
            is FarmTaskDefinition -> old.copy(destinations = change.recipients)
            is FoodTaskDefinition -> old.copy(destinations = change.recipients)
            is MiningTaskDefinition -> old.copy(destinations = change.recipients)
            is TransportTaskDefinition -> old.copy(destinations = change.recipients)
            is DeliveryTaskDefinition -> { require(change.recipients.positions.size == 1) { "delivery redirect requires one recipient" }; old.copy(destination = change.recipients.positions.single()) }
            is LumberjackTaskDefinition -> { require(change.recipients.positions.size == 1) { "wood redirect requires one output" }; old.copy(destination = change.recipients.positions.single()) }
            else -> throw IllegalArgumentException("recipient: operation does not deliver items")
        }
        is TaskChange.Sources -> when (old) {
            is PlantingTaskDefinition -> old.copy(work=old.work.withSources(change.sources))
            is FarmTaskDefinition -> old.copy(work=old.work.copy(seedSources=change.sources))
            is FoodTaskDefinition -> {
                val work=old.work as? FoodWorkOrder.Stored ?: throw IllegalArgumentException("gathering/hunting does not withdraw stored food")
                old.copy(work=work.copy(sources=change.sources ?: throw IllegalArgumentException("stored food requires an authorized source")))
            }
            is TransportTaskDefinition -> old.copy(sources = change.sources ?: throw IllegalArgumentException("transport requires at least one authorized source"))
            is LumberjackTaskDefinition -> old.copy(version = 2, supplySources = change.sources)
            else -> throw IllegalArgumentException("sources: operation does not authorize supply withdrawal")
        }
        is TaskChange.ExtendTime -> withBudget(old, old.budget.copy(ticks = old.budget.ticks + change.ticks))
        is TaskChange.Tactics -> when (old) {
            is AttackTaskDefinition -> old.copy(version = 2, tactics = change.tactics)
            is DefendTaskDefinition -> old.copy(tactics = change.tactics)
            is AreaAttackTaskDefinition -> old.copy(tactics = change.tactics)
            is PatrolTaskDefinition -> old.withTactics(change.tactics)
            else -> old
        }
    }
    fun quantity(definition: TaskDefinition): Int = when (definition) {
        is DeliveryTaskDefinition -> definition.quantity
        is TransportTaskDefinition -> definition.quantity
        is PlantingTaskDefinition -> definition.quantity
        is FarmTaskDefinition -> definition.quantity
        is FoodTaskDefinition -> definition.quantity
        is MiningTaskDefinition -> definition.quantity
        is LumberjackTaskDefinition -> definition.quantity
        is AreaAttackTaskDefinition -> definition.quota
        else -> throw IllegalArgumentException("operation does not have an item/defeat quota")
    }
    fun withBudget(d: TaskDefinition, budget: TaskBudget): TaskDefinition = when (d) {
        is ExplorerTaskDefinition -> d.copy(budget=budget)
        is FishingTaskDefinition -> d.copy(budget=budget)
        is MachineTaskDefinition -> d.copy(budget=budget)
        is PrepareFieldTaskDefinition -> d.copy(budget = budget)
        is PlantingTaskDefinition -> d.copy(budget=budget)
        is FarmTaskDefinition -> d.copy(budget = budget)
        is FoodTaskDefinition -> d.copy(budget = budget)
        is MiningTaskDefinition -> d.copy(budget = budget)
        is InventoryTaskDefinition -> d.copy(budget = budget)
        is NavigateTaskDefinition -> d.copy(budget = budget)
        is DeliveryTaskDefinition -> d.copy(budget = budget)
        is TransportTaskDefinition -> d.copy(budget = budget)
        is LumberjackTaskDefinition -> d.copy(budget = budget)
        is AttackTaskDefinition -> d.copy(budget = budget)
        is DefendTaskDefinition -> d.copy(budget = budget)
        is AreaAttackTaskDefinition -> d.copy(budget = budget)
        is PatrolTaskDefinition -> d.withBudget(budget)
    }
}
