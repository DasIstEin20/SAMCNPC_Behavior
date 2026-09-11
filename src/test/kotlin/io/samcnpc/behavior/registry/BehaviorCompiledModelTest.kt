package io.samcnpc.behavior.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.samcnpc.behavior.model.ActionHandler
import io.samcnpc.behavior.model.BehaviorChannel
import io.samcnpc.behavior.model.CompiledAction
import io.samcnpc.behavior.model.CompiledPack
import io.samcnpc.behavior.model.CompiledRule
import io.samcnpc.behavior.model.ConditionExpression
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.NpcActionResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BehaviorCompiledModelTest {
    @Test
    fun compiledNumericConditionDoesNotRetainMutableJsonArguments() {
        val args = JsonObject().apply { addProperty("operator", "gt"); addProperty("value", 0.5) }
        val definition = BehaviorDefinitions.conditions.getValue("samcnpc:health_fraction")
        assertEquals(null, definition.validateArgs(args))
        val condition = definition.compile(args)
        args.remove("operator")
        args.remove("value")
        assertTrue(condition.evaluate(decisionContext(health = 0.6)))
        assertFalse(condition.evaluate(decisionContext(health = 0.4)))
    }

    @Test
    fun compiledHandlersRemainResolvedWhenInputDefinitionMapsChange() {
        val handler = ActionHandler { _, _, _ -> NpcActionResult.succeeded("bound action") }
        val conditions = BehaviorDefinitions.conditions.toMutableMap()
        val actions = mutableMapOf("test:bound" to ActionDefinition(
            "test:bound", setOf(BehaviorChannel.LOOK), { null }, { handler },
        ))
        val compiled = BehaviorPackCompiler(conditions, actions).compile("binding", document("test:bound"))
        val pack = checkNotNull(compiled.pack) { compiled.report.messages.joinToString() }
        conditions.clear()
        actions.clear()
        assertSame(handler, pack.rules.single().actions.single().handler)
        assertTrue(pack.rules.single().whenExpression.evaluate(decisionContext()))
    }

    @Test
    fun compiledCollectionsAreDefensiveAndCannotBeChangedThroughACast() {
        val channels = mutableSetOf(BehaviorChannel.LOOK)
        val action = CompiledAction("test:look", { _, _, _ -> NpcActionResult.succeeded("look") }, channels)
        val actions = mutableListOf(action)
        val children = mutableListOf<ConditionExpression>(ConditionExpression.Test("test:true") { true })
        val expression = ConditionExpression.All(children)
        val rule = CompiledRule("rule", 0, 0, expression, actions)
        val rules = mutableListOf(rule)
        val pack = CompiledPack("test:immutable", "", 0, channels, rules)
        channels.clear(); actions.clear(); children.clear(); rules.clear()
        assertEquals(setOf(BehaviorChannel.LOOK), action.channels)
        assertEquals(1, pack.rules.single().actions.size)
        assertEquals(1, expression.children.size)
        assertFailsWith<UnsupportedOperationException> { (pack.rules as MutableList<CompiledRule>).clear() }
        assertFailsWith<UnsupportedOperationException> { (action.channels as MutableSet<BehaviorChannel>).clear() }
    }

    @Test
    fun missingRequiredStringsAreValidationErrorsWithoutRuntimeExceptions() {
        for (missing in listOf("id", "description")) {
            val json = JsonParser.parseString(document("samcnpc:look_at_summoner")).asJsonObject
            json.remove(missing)
            val result = BehaviorDefinitions.compiler.compile("missing-$missing", json.toString())
            assertFalse(result.report.accepted)
            assertTrue(result.report.messages.single().contains(missing))
        }
        val json = JsonParser.parseString(document("samcnpc:look_at_summoner")).asJsonObject
        json.getAsJsonArray("rules")[0].asJsonObject.getAsJsonArray("actions")[0].asJsonObject.remove("action")
        assertFalse(BehaviorDefinitions.compiler.compile("missing-action", json.toString()).report.accepted)
    }

    @Test
    fun movementWithoutLookPermissionIsRejectedWithPackContext() {
        val json = document("samcnpc:move_to_target")
            .replace("\"look\"", "\"movement\"")
            .replace("\"action\":\"samcnpc:move_to_target\"", "\"action\":\"samcnpc:move_to_target\",\"args\":{\"speed\":1.0,\"stopDistance\":2.0}")
        val result = BehaviorDefinitions.compiler.compile("missing-look", json)
        assertFalse(result.report.accepted)
        assertTrue(result.report.messages.any { it.contains("missing-look") && it.contains("outside the pack channels") })
    }

    @Test
    fun allMaintainedBuiltInsCompileThroughTheSameDefinitionRegistry() {
        for (name in listOf("idle_look", "follow_summoner", "retaliate", "demo_lumberjack")) {
            val resource = checkNotNull(javaClass.classLoader.getResourceAsStream("data/samcnpc_behavior/behaviors/$name.json"))
            val json = resource.bufferedReader().use { it.readText() }
            val result = BehaviorDefinitions.compiler.compile(name, json)
            assertTrue(result.report.accepted, result.report.messages.joinToString())
        }
    }

    private fun document(action: String) = """{"schemaVersion":1,"id":"test:pack","description":"test",
        "priority":0,"channels":["look"],"rules":[{"id":"rule","priority":0,
        "when":{"test":{"condition":"samcnpc:always"}},"actions":[{"action":"$action"}]}]}"""
}
