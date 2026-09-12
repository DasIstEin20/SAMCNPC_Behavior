package io.samcnpc.behavior.registry

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Loading boundary only: reject oversized/ambiguous input before building a JSON tree. */
internal object BoundedBehaviorJson {
    const val MAX_BYTES = 128 * 1024
    const val MAX_DEPTH = 32
    const val MAX_NODES = 16384
    const val MAX_STRING_CODEPOINTS = 512
    const val MAX_NUMBER_CHARS = 64

    fun parse(text: String): JsonElement {
        if (text.length > MAX_BYTES) throw JsonParseException("input exceeds $MAX_BYTES UTF-8 bytes")
        val bytes = try { utf8Size(text) }
        catch (error: CharacterCodingException) { throw JsonParseException("input has invalid Unicode", error) }
        if (bytes > MAX_BYTES) throw JsonParseException("input exceeds $MAX_BYTES UTF-8 bytes")
        checkLexicalForm(text)
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                val value = Cursor(reader).value(0)
                if (reader.peek() != JsonToken.END_DOCUMENT) throw JsonParseException("trailing content after JSON value")
                return value
            }
        } catch (error: IOException) {
            throw JsonParseException("invalid JSON: ${error.message?.take(512)}", error)
        } catch (error: NumberFormatException) {
            throw JsonParseException("invalid numeric or Unicode escape", error)
        }
    }

    fun read(input: InputStream): String {
        // The bound applies to the opened stream, including a file that grows after discovery.
        val bytes = input.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw IOException("input exceeds $MAX_BYTES bytes")
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun utf8Size(value: String): Int = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value)).remaining()

    // Gson 2.10 accepts case-insensitive literals and raw string controls even without leniency.
    // Check those lexical cases once; JsonReader still owns escapes, numbers and JSON grammar.
    private fun checkLexicalForm(text: String) {
        var quoted = false
        var escaped = false
        for ((index, character) in text.withIndex()) {
            if (quoted) {
                if (character.code < 32) throw JsonParseException("unescaped control character at offset $index")
                if (escaped) {
                    if (character !in "\"\\/bfnrtu") throw JsonParseException("invalid string escape at offset $index")
                    escaped = false
                } else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else if (character == '"') quoted = true
            else if (index == 0 && character == '\uFEFF') continue
            else if ((character == 't' && !text.startsWith("true", index)) ||
                (character == 'f' && !text.startsWith("false", index)) ||
                (character == 'n' && !text.startsWith("null", index))) {
                throw JsonParseException("invalid JSON literal at offset $index")
            } else if (character !in "{}[],:-+.0123456789eEtruefalsn \t\r\n") {
                throw JsonParseException("invalid JSON character at offset $index")
            }
        }
    }

    private class Cursor(private val reader: JsonReader) {
        private var nodes = 0
        fun value(depth: Int): JsonElement {
            if (depth > MAX_DEPTH) fail("JSON nesting exceeds $MAX_DEPTH")
            if (++nodes > MAX_NODES) fail("JSON value count exceeds $MAX_NODES")
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val result = JsonObject()
                    while (reader.hasNext()) {
                        val name = checkedString(reader.nextName())
                        if (result.has(name)) fail("duplicate property '$name'")
                        result.add(name, value(depth + 1))
                    }
                    reader.endObject()
                    result
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    val result = JsonArray()
                    while (reader.hasNext()) result.add(value(depth + 1))
                    reader.endArray()
                    result
                }
                JsonToken.STRING -> JsonPrimitive(checkedString(reader.nextString()))
                JsonToken.NUMBER -> {
                    val token = reader.nextString()
                    if (token.length > MAX_NUMBER_CHARS) fail("number exceeds $MAX_NUMBER_CHARS characters")
                    JsonPrimitive(BigDecimal(token))
                }
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> fail("expected a JSON value")
            }
        }

        private fun checkedString(value: String): String {
            if (value.codePointCount(0, value.length) > MAX_STRING_CODEPOINTS) fail("string exceeds $MAX_STRING_CODEPOINTS code points")
            try { utf8Size(value) }
            catch (error: CharacterCodingException) { fail("string contains an unpaired surrogate") }
            return value
        }
        private fun fail(message: String): Nothing = throw JsonParseException("${reader.path.take(256)}: ${message.take(512)}")
    }
}

/** Never round a fractional schema integer through Double before validating it. */
internal fun JsonElement?.exactLongOrNull(): Long? {
    if (this == null || !isJsonPrimitive || !asJsonPrimitive.isNumber) return null
    val token = asString
    if (token.length > BoundedBehaviorJson.MAX_NUMBER_CHARS) return null
    return try { BigDecimal(token).longValueExact() }
    catch (error: NumberFormatException) { null }
    catch (error: ArithmeticException) { null }
}
