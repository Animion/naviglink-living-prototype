package cz.naviglink.driver.crypto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Kanonická JSON serializace — byte-by-byte identická s Python:
 *   json.dumps(payload, sort_keys=True, separators=(",",":"), ensure_ascii=False)
 *
 * a s JS:
 *   canonicalJson(value) — recurzivní sort + JSON.stringify pro primitivy
 *
 * Pravidla:
 *   - klíče seřazené alfabeticky (lexikograficky podle UTF-16 code units jako v Python)
 *   - žádné mezery mezi separátory: '{"a":1,"b":2}'
 *   - non-ASCII chars passují (UTF-8 ve výstupních bajtech), ne `\uXXXX` escape
 *   - integery jako '42' (bez '.0'), floats jako '4.2' nebo '4.0'
 *   - true/false/null beze změny
 *
 * Výstup: UTF-8 bajt-sekvence pro podpisování.
 */
object CanonicalJson {

    fun encodeToBytes(element: JsonElement): ByteArray =
        encodeToString(element).toByteArray(Charsets.UTF_8)

    fun encodeToString(element: JsonElement): String {
        val sb = StringBuilder()
        write(sb, element)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, element: JsonElement) {
        when (element) {
            is JsonNull -> sb.append("null")
            is JsonObject -> writeObject(sb, element)
            is JsonArray -> writeArray(sb, element)
            is JsonPrimitive -> writePrimitive(sb, element)
        }
    }

    private fun writeObject(sb: StringBuilder, obj: JsonObject) {
        sb.append('{')
        val sortedKeys = obj.keys.sorted()  // Kotlin String.sorted() je code-unit ordering
        var first = true
        for (key in sortedKeys) {
            if (!first) sb.append(',')
            writeJsonString(sb, key)
            sb.append(':')
            write(sb, obj.getValue(key))
            first = false
        }
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, arr: JsonArray) {
        sb.append('[')
        var first = true
        for (item in arr) {
            if (!first) sb.append(',')
            write(sb, item)
            first = false
        }
        sb.append(']')
    }

    private fun writePrimitive(sb: StringBuilder, prim: JsonPrimitive) {
        if (prim.isString) {
            writeJsonString(sb, prim.content)
            return
        }
        // boolean / null
        prim.booleanOrNull?.let { sb.append(if (it) "true" else "false"); return }
        if (prim.contentOrNull == "null") { sb.append("null"); return }
        // číslo — Python json formátuje integer jako '1', float jako '1.0', '1.5'
        // Kotlin Double "1.0" produkuje "1.0", což je shoda. Long "1" produkuje "1".
        // Pokud je `content` přímo platné JSON číslo, předáme as-is.
        sb.append(prim.content)
    }

    /**
     * JSON string s ensure_ascii=False. Escape jen: \" \\ \b \f \n \r \t a control chars
     * < 0x20. UTF-8 multibyte sekvence pass-through.
     */
    private fun writeJsonString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
            i++
        }
        sb.append('"')
    }
}
