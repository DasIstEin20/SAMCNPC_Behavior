package io.samcnpc.behavior.model

/**
 * An action that claims multiple channels is atomic: it runs only when it can reserve all of
 * them. This prevents a lower-priority attack from mixing with a higher-priority hand action.
 */
object BehaviorArbiter {
    fun choose(intents: List<ActionIntent>): List<ActionIntent> {
        if (intents.isEmpty()) {
            return emptyList()
        }
        val occupied = mutableSetOf<BehaviorChannel>()
        val selected = ArrayList<ActionIntent>()
        for (intent in intents.sortedWith(ActionIntent.WINNER_FIRST)) {
            if (intent.channels.none { it in occupied }) {
                selected.add(intent)
                occupied.addAll(intent.channels)
            }
        }
        return selected
    }
}
