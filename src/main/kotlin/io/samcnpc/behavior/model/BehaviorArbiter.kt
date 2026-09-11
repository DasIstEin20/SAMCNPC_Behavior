package io.samcnpc.behavior.model

/** A candidate reserves every channel or none, before any selected action is executed. */
object BehaviorArbiter {
    fun choose(intents: List<ActionIntent>): List<ActionIntent> =
        chooseOrdered(intents.sortedWith(ActionIntent.WINNER_FIRST))

    internal fun chooseOrdered(intents: List<ActionIntent>): List<ActionIntent> {
        var occupied = 0
        val selected = ArrayList<ActionIntent>()
        for (intent in intents) {
            val channels = intent.action.channelMask
            if (occupied and channels == 0) {
                selected.add(intent)
                occupied = occupied or channels
            }
        }
        return selected
    }
}
