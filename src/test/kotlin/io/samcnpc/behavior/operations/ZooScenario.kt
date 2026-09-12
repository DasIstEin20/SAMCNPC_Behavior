package io.samcnpc.behavior.operations

internal interface ZooScenario {
    val scene: OperationScene
    val complete: Boolean
    fun tick()
    fun evidence(): Map<String, Any?>
}
