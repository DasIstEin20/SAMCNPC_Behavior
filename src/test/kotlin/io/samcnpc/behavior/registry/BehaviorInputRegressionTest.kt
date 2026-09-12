package io.samcnpc.behavior.registry

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compiles against the pre-fix public gateway too, for an actual failing reproduction. */
class BehaviorInputRegressionTest {
    @Test
    fun `public candidate gateway rejects ambiguous and lenient documents`() {
        val valid = document()
        val candidates = listOf(
            "duplicate ID" to valid.replace("\"id\":\"samcnpc:bounded_input\"", "\"id\":\"samcnpc:other\",\"id\":\"samcnpc:bounded_input\""),
            "escaped duplicate" to valid.replace("\"id\":\"samcnpc:bounded_input\"", "\"id\":\"samcnpc:other\",\"\\u0069d\":\"samcnpc:bounded_input\""),
            "comment" to "/*not JSON*/$valid",
            "raw newline" to valid.replace("\"bounded\"", "\"raw\nline\""),
        )
        val incorrectlyAccepted = candidates.filter { (_, body) ->
            io.samcnpc.behavior.api.BehaviorPackValidationApi.validateCandidate(body, "gateway-regression").accepted
        }.map { it.first }
        assertTrue(incorrectlyAccepted.isEmpty(), "Gateway accepted invalid input: $incorrectlyAccepted")
        val diagnostic = io.samcnpc.behavior.api.BehaviorPackValidationApi.validateCandidate("{}", "\n" + "s".repeat(4000)).messages.single()
        assertTrue(diagnostic.length <= 2048 && '\n' !in diagnostic, "Diagnostic label was not bounded/sanitized")
        val escapedControlKey = valid.replaceFirst("\"schemaVersion\"", "\"bad\\nproperty\"")
        val fieldDiagnostic = io.samcnpc.behavior.api.BehaviorPackValidationApi.validateCandidate(escapedControlKey).messages.single()
        assertTrue("bad property" in fieldDiagnostic && '\n' !in fieldDiagnostic, fieldDiagnostic)
    }

    @Test
    fun `description length counts Unicode code points as the schema does`() {
        val valid = document(description = "😀".repeat(512))
        assertTrue(BehaviorDefinitions.compiler.compile("unicode", valid).report.accepted)
        assertFalse(BehaviorDefinitions.compiler.compile("unicode", document(description = "😀".repeat(513))).report.accepted)
    }

    @Test
    fun `schema integers are exact including registered condition and action parameters`() {
        for (integer in listOf("0", "-0.0", "1.0", "1e2")) {
            assertTrue(BehaviorDefinitions.compiler.compile("exact", document(priority = integer)).report.accepted, integer)
        }
        for (fraction in listOf("1.0000000000000001", "0.0000000000000000000001", "1e1000000000")) {
            assertFalse(BehaviorDefinitions.compiler.compile("fraction", document(priority = fraction)).report.accepted, fraction)
        }
        val condition = document().replace("\"condition\":\"samcnpc:always\"", "\"condition\":\"samcnpc:was_hurt_recently\",\"args\":{\"withinTicks\":20.0000000000000001}")
        assertFalse(BehaviorDefinitions.compiler.compile("fraction-condition", condition).report.accepted)
        val action = document().replace("\"look\"", "\"combat\"").replace("\"action\":\"samcnpc:look_at_summoner\"", "\"action\":\"samcnpc:set_attack_target_from_recent_attacker\",\"args\":{\"durationTicks\":20.0000000000000001}")
        val rejected = BehaviorDefinitions.compiler.compile("fraction-action", action).report
        assertFalse(rejected.accepted)
        assertTrue(rejected.messages.any { "durationTicks" in it }, rejected.messages.joinToString())
    }

    private fun document(priority: String = "0", description: String = "bounded") = """
        {"schemaVersion":1,"id":"samcnpc:bounded_input","description":"$description","priority":$priority,
        "channels":["look"],"rules":[{"id":"rule","priority":0,"when":{"test":{"condition":"samcnpc:always"}},
        "actions":[{"action":"samcnpc:look_at_summoner"}]}]}
    """.trimIndent()
}
