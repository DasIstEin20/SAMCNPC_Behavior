package io.samcnpc.behavior.registry

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BehaviorPackCompilerTest {
    @Test
    fun `valid bounded pack compiles`() {
        val result = BehaviorDefinitions.compiler.compile("test", """
            {
              "schemaVersion": 1,
              "id": "samcnpc:test",
              "description": "test",
              "priority": 0,
              "channels": ["look"],
              "rules": [{
                "id": "look",
                "priority": 0,
                "when": {"test": {"condition": "samcnpc:always"}},
                "actions": [{"action": "samcnpc:look_at_summoner"}]
              }]
            }
        """.trimIndent())

        assertTrue(result.report.accepted, result.report.messages.joinToString())
        assertTrue(result.pack != null)
    }

    @Test
    fun `unknown action reports precise rule context`() {
        val result = BehaviorDefinitions.compiler.compile("bad-pack", """
            {
              "schemaVersion": 1,
              "id": "samcnpc:bad",
              "description": "test",
              "priority": 0,
              "channels": ["look"],
              "rules": [{
                "id": "bad",
                "priority": 0,
                "when": {"test": {"condition": "samcnpc:always"}},
                "actions": [{"action": "samcnpc:not_registered"}]
              }]
            }
        """.trimIndent())

        assertFalse(result.report.accepted)
        assertTrue(result.report.messages.single().contains("bad-pack rule[0] action[0]"))
        assertTrue(result.report.messages.single().contains("unknown action"))
    }

    @Test
    fun `extra executable shaped data is structurally rejected`() {
        val result = BehaviorDefinitions.compiler.compile("unsafe", """
            {
              "schemaVersion": 1,
              "id": "samcnpc:unsafe",
              "description": "test",
              "priority": 0,
              "channels": ["look"],
              "script": "do evil",
              "rules": [{
                "id": "look",
                "priority": 0,
                "when": {"test": {"condition": "samcnpc:always"}},
                "actions": [{"action": "samcnpc:look_at_summoner"}]
              }]
            }
        """.trimIndent())

        assertFalse(result.report.accepted)
        assertTrue(result.report.messages.single().contains("unknown property 'script'"))
    }

    @Test
    fun `lumberjack deployment demo is a valid bounded behavior pack`() {
        val source = checkNotNull(javaClass.classLoader.getResourceAsStream("data/samcnpc_behavior/behaviors/demo_lumberjack.json")) {
            "Missing built-in lumberjack demo pack"
        }.bufferedReader().use { it.readText() }

        val result = BehaviorDefinitions.compiler.compile("demo_lumberjack", source)

        assertTrue(result.report.accepted, result.report.messages.joinToString())
        assertTrue(result.pack?.id == "samcnpc:demo_lumberjack")
    }
}
