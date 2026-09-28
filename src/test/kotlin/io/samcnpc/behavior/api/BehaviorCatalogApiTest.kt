package io.samcnpc.behavior.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.samcnpc.behavior.registry.BehaviorDefinitions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BehaviorCatalogApiTest {
    @Test
    fun catalogMatchesRegistryAndIsImmutable() {
        val catalog = BehaviorCatalogApi.snapshot()
        assertSame(catalog, BehaviorCatalogApi.snapshot())
        assertEquals(2, catalog.catalogVersion)
        assertEquals(1, catalog.documentVersion)
        assertEquals(1, catalog.definitionSemanticsVersion)
        assertEquals(25, catalog.conditions.size)
        assertEquals(25, catalog.actions.size)
        assertEquals(BehaviorDefinitions.conditions.keys.sorted(), catalog.conditions.map { it.id })
        assertEquals(BehaviorDefinitions.actions.keys.sorted(), catalog.actions.map { it.id })
        assertTrue(catalog.conditions.all { it.kind == BehaviorComponentKind.CONDITION && it.channels.isEmpty() && it.version == 1 })
        for (entry in catalog.actions) {
            assertEquals(BehaviorComponentKind.ACTION, entry.kind)
            assertEquals(BehaviorDefinitions.actions.getValue(entry.id).channels.map { it.name.lowercase() }.sorted(), entry.channels)
            assertEquals(1, entry.version)
        }
        assertFailsWith<UnsupportedOperationException> { (catalog.actions as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (catalog.conditions as MutableList<*>).clear() }
        val move = catalog.actions.single { it.id == "samcnpc:move_to_target" }
        assertFailsWith<UnsupportedOperationException> { (move.parameters as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (move.channels as MutableList<*>).clear() }
        val choice = catalog.conditions.flatMap { it.parameters }.filterIsInstance<BehaviorParameter.Choice>().first()
        assertFailsWith<UnsupportedOperationException> { (choice.values as MutableList<*>).clear() }
    }

    @Test
    fun allParameterContractsMatchActualValidators() {
        val catalog = BehaviorCatalogApi.snapshot()
        for (entry in catalog.conditions + catalog.actions) {
            val validate = if (entry.kind == BehaviorComponentKind.CONDITION) BehaviorDefinitions.conditions.getValue(entry.id).validateArgs
                else BehaviorDefinitions.actions.getValue(entry.id).validateArgs
            val args = baseline(entry)
            assertNull(validate(args), entry.id)
            val unknown = args.deepCopy()
            unknown.addProperty("unlisted", true)
            assertNotNull(validate(unknown), entry.id + " accepted an undocumented parameter")
            for (parameter in entry.parameters) {
                if (parameter.required) {
                    val missing = args.deepCopy()
                    missing.remove(parameter.name)
                    assertNotNull(validate(missing), entry.id + " requires " + parameter.name)
                }
                val wrong = args.deepCopy()
                wrong.add(parameter.name, JsonObject())
                assertNotNull(validate(wrong), entry.id + " accepted an object for " + parameter.name)
                when (parameter) {
                    is BehaviorParameter.Text -> {
                        val validText = args.deepCopy(); validText.addProperty(parameter.name, parameter.example)
                        assertNull(validate(validText), entry.id)
                        for (text in listOf("", "x".repeat(parameter.maximumLength + 1), "@invented", "minecraft:coal|minecraft:coal")) {
                            val badText = args.deepCopy(); badText.addProperty(parameter.name, text)
                            assertNotNull(validate(badText), entry.id)
                        }
                    }
                    is BehaviorParameter.Numeric -> {
                        for (value in listOf(parameter.minimum, parameter.maximum)) {
                            val boundary = args.deepCopy()
                            boundary.addProperty(parameter.name, value)
                            assertNull(validate(boundary), entry.id + " rejected " + parameter.name + "=" + value)
                        }
                        for (value in listOf(parameter.minimum - 1.0, parameter.maximum + 1.0)) {
                            val outside = args.deepCopy()
                            outside.addProperty(parameter.name, value)
                            assertNotNull(validate(outside), entry.id + " accepted " + parameter.name + "=" + value)
                        }
                        if (parameter.kind == BehaviorNumericKind.INTEGER) {
                            val fractional = args.deepCopy()
                            fractional.add(parameter.name, JsonParser.parseString(parameter.minimum.toLong().toString() + ".0000000000000001"))
                            assertNotNull(validate(fractional), entry.id + " rounded " + parameter.name)
                        }
                    }
                    is BehaviorParameter.Choice -> {
                        for (value in parameter.values) {
                            val accepted = args.deepCopy()
                            accepted.addProperty(parameter.name, value)
                            assertNull(validate(accepted), entry.id + " rejected choice " + value)
                        }
                        val bad = args.deepCopy()
                        bad.addProperty(parameter.name, "not_registered")
                        assertNotNull(validate(bad), entry.id)
                    }
                    is BehaviorParameter.Flag -> {
                        for (value in listOf(false, true)) {
                            val accepted = args.deepCopy()
                            accepted.addProperty(parameter.name, value)
                            assertNull(validate(accepted), entry.id)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun optionalDefaultsAndRelatedFollowDistanceAreExplicit() {
        val catalog = BehaviorCatalogApi.snapshot()
        val follow = catalog.actions.single { it.id == "samcnpc:move_to_summoner" }
        val start = follow.parameters.single { it.name == "startDistance" } as BehaviorParameter.Numeric
        assertTrue(!start.required)
        assertEquals(BehaviorNumberDefault.FromParameter("stopDistance", 2.0), start.defaultValue)
        assertEquals("stopDistance", start.greaterThanParameter)
        val args = baseline(follow)
        args.addProperty("stopDistance", 8.0)
        assertNull(BehaviorDefinitions.actions.getValue(follow.id).validateArgs(args))
        args.addProperty("startDistance", 8.0)
        assertNotNull(BehaviorDefinitions.actions.getValue(follow.id).validateArgs(args))
        args.addProperty("startDistance", 10.0)
        assertNull(BehaviorDefinitions.actions.getValue(follow.id).validateArgs(args))
        val reaction = catalog.actions.single { it.id == "samcnpc:set_attack_target_from_recent_attacker" }
        assertTrue(reaction.parameters.none { it.required })
        assertEquals(BehaviorNumberDefault.Fixed(24.0), (reaction.parameters.single { it.name == "leash" } as BehaviorParameter.Numeric).defaultValue)
        assertEquals(BehaviorNumberDefault.Fixed(600.0), (reaction.parameters.single { it.name == "durationTicks" } as BehaviorParameter.Numeric).defaultValue)
        assertEquals(false, (reaction.parameters.single { it.name == "allowPlayers" } as BehaviorParameter.Flag).defaultValue)
        val explicit = JsonObject()
        for (parameter in reaction.parameters) {
            when (parameter) {
                is BehaviorParameter.Text -> args.addProperty(parameter.name, parameter.example)
                is BehaviorParameter.Numeric -> explicit.addProperty(parameter.name, (parameter.defaultValue as BehaviorNumberDefault.Fixed).value)
                is BehaviorParameter.Flag -> explicit.addProperty(parameter.name, parameter.defaultValue)
                is BehaviorParameter.Choice -> error("No choice default in this operation")
            }
        }
        assertNull(BehaviorDefinitions.actions.getValue(reaction.id).validateArgs(explicit))
    }

    private fun baseline(entry: BehaviorComponentDescriptor): JsonObject {
        val args = JsonObject()
        for (parameter in entry.parameters.filter { it.required }) {
            when (parameter) {
                is BehaviorParameter.Text -> args.addProperty(parameter.name, parameter.example)
                is BehaviorParameter.Numeric -> args.addProperty(parameter.name, parameter.minimum)
                is BehaviorParameter.Choice -> args.addProperty(parameter.name, parameter.values.first())
                is BehaviorParameter.Flag -> args.addProperty(parameter.name, parameter.defaultValue ?: false)
            }
        }
        return args
    }
}
