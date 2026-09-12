package io.samcnpc.behavior.registry

import com.google.gson.JsonParseException
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedBehaviorJsonTest {
    @Test
    fun `duplicate names and non JSON syntax are rejected before semantics`() {
        val invalid = listOf(
            "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}",
            "{\"a\":{\"b\":1,\"b\":2}}", "{\"a\":TRUE}", "{\"a\":False}",
            "{\"a\":NULL}", "{\"a\":truE}", "{\"a\":falsE}",
            "{\"a\":\"raw\nline\"}", "{\"a\":\"bad\\'escape\"}",
            "{/*comment*/\"a\":1}", "{'a':1}", "{a:1}", "{\"a\":01}",
            "{\"a\":NaN}", "{\"a\":1,}", "[1,]", "{}{}", "{\"a\":", "\u000c{}",
            "{\"a\":\"\\ud800\"}", "{\"a\":\"\\uxxxx\"}",
        )
        for (text in invalid) assertFailsWith<JsonParseException>(text) { BoundedBehaviorJson.parse(text) }
        val valid = BoundedBehaviorJson.parse("{\"a\":true,\"b\":null,\"c\":-1.5e+2,\"d\":\"ok\\n\\uD83D\\uDE00\"}").asJsonObject
        assertTrue(valid.get("a").asBoolean)
        assertEquals(-150.0, valid.get("c").asDouble)
        assertEquals("ok\n😀", valid.get("d").asString)
    }

    @Test
    fun `input tree and token limits apply before semantic compilation`() {
        val limit = BoundedBehaviorJson.MAX_BYTES
        assertTrue(BoundedBehaviorJson.parse("{}" + " ".repeat(limit - 2)).isJsonObject)
        assertFailsWith<JsonParseException> { BoundedBehaviorJson.parse("{}" + " ".repeat(limit - 1)) }
        assertFailsWith<JsonParseException> { BoundedBehaviorJson.parse("\"" + "é".repeat(limit / 2) + "\"") }
        assertFailsWith<JsonParseException> { BoundedBehaviorJson.parse("[".repeat(40) + "0" + "]".repeat(40)) }
        assertFailsWith<JsonParseException> { BoundedBehaviorJson.parse("[" + List(BoundedBehaviorJson.MAX_NODES) { "0" }.joinToString(",") + "]") }
        assertFailsWith<JsonParseException> { BoundedBehaviorJson.parse("\"" + "a".repeat(513) + "\"") }
        assertFailsWith<JsonParseException> { BoundedBehaviorJson.parse("1".repeat(65)) }
    }

    @Test
    fun `stream read is capped and malformed UTF8 fails explicitly`() {
        assertEquals("é", BoundedBehaviorJson.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0xa9.toByte()))))
        assertFailsWith<IOException> { BoundedBehaviorJson.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte()))) }
        assertEquals(BoundedBehaviorJson.MAX_BYTES, BoundedBehaviorJson.read(ByteArrayInputStream(ByteArray(BoundedBehaviorJson.MAX_BYTES) { 32 })).length)
        val unending = object : InputStream() {
            var reads = 0
            override fun read(): Int { reads++; return 32 }
        }
        assertFailsWith<IOException> { BoundedBehaviorJson.read(unending) }
        assertEquals(BoundedBehaviorJson.MAX_BYTES + 1, unending.reads)
    }

}
