package io.samcnpc.behavior.registry

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.samcnpc.behavior.api.BehaviorCatalogApi
import io.samcnpc.behavior.api.BehaviorPackValidationApi
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests declared structural limits against the real gateway without invoking action handlers. */
class BehaviorSchemaBoundaryTest {
    @Test
    fun schemaCollectionAndIdentifierBoundariesAgreeWithRuntime() {
        val schema = schema()
        val properties = schema.getAsJsonObject("properties")
        assertEquals(BehaviorCatalogApi.snapshot().documentVersion, properties.getAsJsonObject("schemaVersion").get("const").asInt)
        val ruleMax = properties.getAsJsonObject("rules").get("maxItems").asInt
        for (count in listOf(ruleMax, ruleMax + 1)) {
            val root = document()
            val rules = JsonArray()
            repeat(count) { index -> rules.add(rule().also { it.addProperty("id", "rule_" + index) }) }
            root.add("rules", rules)
            assertAccepted(root, count == ruleMax)
        }
        val definitions = schema.getAsJsonObject("\$defs")
        val actionMax = definitions.getAsJsonObject("rule").getAsJsonObject("properties").getAsJsonObject("actions").get("maxItems").asInt
        for (count in listOf(actionMax, actionMax + 1)) {
            val root = document()
            val actions = JsonArray()
            repeat(count) { actions.add(action()) }
            firstRule(root).add("actions", actions)
            assertAccepted(root, count == actionMax)
        }
        val idMax = properties.getAsJsonObject("id").get("maxLength").asInt
        for (count in listOf(idMax, idMax + 1)) {
            val root = document()
            root.addProperty("id", "test:" + "a".repeat(count - 5))
            assertAccepted(root, count == idMax)
        }
        val ruleIdMax = definitions.getAsJsonObject("rule").getAsJsonObject("properties").getAsJsonObject("id").get("maxLength").asInt
        for (count in listOf(ruleIdMax, ruleIdMax + 1)) {
            val root = document()
            firstRule(root).addProperty("id", "r".repeat(count))
            assertAccepted(root, count == ruleIdMax)
        }
    }

    @Test
    fun conditionDepthWidthPriorityAndCooldownBoundsAreEnforced() {
        for (depth in listOf(7, 8)) {
            val root = document()
            var expression = condition()
            repeat(depth) { expression = JsonObject().also { wrapper -> wrapper.add("not", expression) } }
            firstRule(root).add("when", expression)
            assertAccepted(root, depth == 7)
        }
        for (count in listOf(16, 17)) {
            val children = JsonArray()
            repeat(count) { children.add(condition()) }
            val root = document()
            firstRule(root).add("when", JsonObject().also { it.add("all", children) })
            assertAccepted(root, count == 16)
        }
        for (priority in listOf(-100001, -100000, 100000, 100001)) {
            val root = document()
            root.addProperty("priority", priority)
            firstRule(root).addProperty("priority", priority)
            assertAccepted(root, priority in -100000..100000)
        }
        for (cooldown in listOf(-1, 0, 120000, 120001)) {
            val root = document()
            firstRule(root).addProperty("cooldownTicks", cooldown)
            assertAccepted(root, cooldown in 0..120000)
        }
        for (expression in listOf(JsonObject(), JsonObject().also { it.add("all", JsonArray()) })) {
            val root = document()
            firstRule(root).add("when", expression)
            assertAccepted(root, false)
        }
    }

    @Test
    fun missingExtraMistypedAndUnsupportedFieldsAreRejected() {
        for (name in listOf("schemaVersion", "id", "description", "priority", "channels", "rules")) {
            val root = document()
            root.remove(name)
            assertAccepted(root, false)
        }
        for (name in listOf("id", "priority", "when", "actions")) {
            val root = document()
            firstRule(root).remove(name)
            assertAccepted(root, false)
        }
        val extra = document();extra.addProperty("script", "ignored execution data");assertAccepted(extra, false)
        val unknown = document();unknown.addProperty("schemaVersion", 2);assertAccepted(unknown, false)
        val invalidId = document();invalidId.addProperty("id", "../outside");assertAccepted(invalidId, false)
        val unsupported = document();unsupported.add("channels", JsonParser.parseString("[\"teleport\"]"));assertAccepted(unsupported, false)
        val duplicates = document();duplicates.add("channels", JsonParser.parseString("[\"look\",\"look\"]"));assertAccepted(duplicates, false)
        val duplicateRules = document();duplicateRules.getAsJsonArray("rules").add(rule());assertAccepted(duplicateRules, false)
        val nullArgs = document();firstRule(nullArgs).getAsJsonArray("actions")[0].asJsonObject.add("args", com.google.gson.JsonNull.INSTANCE);assertAccepted(nullArgs, false)
        val wrongPriority = document();wrongPriority.addProperty("priority", true);assertAccepted(wrongPriority, false)
        val args = document();firstRule(args).getAsJsonArray("actions")[0].asJsonObject.add("args", JsonParser.parseString("{\"class\":\"not.a.Class\"}"));assertAccepted(args, false)
    }

    private fun schema(): JsonObject {
        val local = Path.of("contracts/behavior-pack.schema.json")
        val path = if (Files.isRegularFile(local)) local else Path.of("../contracts/behavior-pack.schema.json")
        return JsonParser.parseString(Files.readString(path)).asJsonObject
    }

    private fun assertAccepted(root: JsonObject, expected: Boolean) {
        val report = BehaviorPackValidationApi.validateCandidate(root.toString(), "schema-boundary")
        assertEquals(expected, report.accepted, report.messages.joinToString())
        if (!expected) assertTrue(report.messages.any { it.startsWith("schema-boundary:") })
    }

    private fun firstRule(root: JsonObject) = root.getAsJsonArray("rules")[0].asJsonObject
    private fun document() = JsonParser.parseString("""{"schemaVersion":1,"id":"test:boundary","description":"boundary",
        "priority":0,"channels":["look"],"rules":[]}""").asJsonObject.also { it.getAsJsonArray("rules").add(rule()) }
    private fun rule() = JsonObject().also {
        it.addProperty("id", "rule")
        it.addProperty("priority", 0)
        it.add("when", condition())
        it.add("actions", JsonArray().also { actions -> actions.add(action()) })
    }
    private fun condition() = JsonParser.parseString("""{"test":{"condition":"samcnpc:always"}}""").asJsonObject
    private fun action() = JsonParser.parseString("""{"action":"samcnpc:look_at_summoner"}""").asJsonObject
}
