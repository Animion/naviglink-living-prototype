package cz.naviglink.driver

import cz.naviglink.driver.crypto.CanonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tyto testy ověřují, že Kotlin canonical JSON encoder produkuje *byte-by-byte*
 * stejnou sekvenci jako Python:
 *
 *   json.dumps(payload, sort_keys=True, separators=(",",":"), ensure_ascii=False).encode("utf-8")
 *
 * Bez této shody by Ed25519 podpis z Androidu neprošel server-side verifikací.
 */
class CanonicalJsonTest {

    private val json = Json { prettyPrint = false }

    private fun canonical(input: String): String {
        val parsed = json.parseToJsonElement(input)
        return CanonicalJson.encodeToString(parsed)
    }

    @Test
    fun `empty object`() {
        assertEquals("{}", canonical("{}"))
    }

    @Test
    fun `simple object keys sorted`() {
        assertEquals(
            """{"a":1,"b":2,"c":3}""",
            canonical("""{"c":3,"a":1,"b":2}"""),
        )
    }

    @Test
    fun `nested object also sorted`() {
        assertEquals(
            """{"a":{"x":1,"y":2},"b":3}""",
            canonical("""{"b":3,"a":{"y":2,"x":1}}"""),
        )
    }

    @Test
    fun `arrays preserve order`() {
        assertEquals(
            """{"items":[3,1,2]}""",
            canonical("""{"items":[3,1,2]}"""),
        )
    }

    @Test
    fun `strings with non-ascii UTF-8 pass through`() {
        // ensure_ascii=False — žádné \uXXXX escape pro Veveří
        assertEquals(
            """{"ulice":"Veveří"}""",
            canonical("""{"ulice":"Veveří"}"""),
        )
    }

    @Test
    fun `escape only required control chars`() {
        // newline, tab, quotes, backslash
        val input = "\"" + "hello\\nworld\\t\\\"end\\\"" + "\""
        val parsed = json.parseToJsonElement(input)
        val encoded = CanonicalJson.encodeToString(parsed)
        assertEquals("\"hello\\nworld\\t\\\"end\\\"\"", encoded)
    }

    @Test
    fun `null preserved`() {
        assertEquals("""{"valid_to":null}""", canonical("""{"valid_to":null}"""))
    }

    @Test
    fun `booleans preserved`() {
        assertEquals("""{"ok":true}""", canonical("""{"ok":true}"""))
        assertEquals("""{"ok":false}""", canonical("""{"ok":false}"""))
    }

    @Test
    fun `signed subject canonical matches Python format`() {
        // Plný SignedSubject struct — měl by se zakódovat stejně jako:
        //   json.dumps(d, sort_keys=True, separators=(",",":"), ensure_ascii=False)
        val input = """
            {
                "payload": {"ulice": "Veveří", "typ": "blokove_cisteni"},
                "kind": "subject",
                "valid_from": "2026-06-09T06:00:00.000000Z",
                "valid_to": "2026-06-09T10:00:00.000000Z",
                "references": {"domain": "traffic_cz"},
                "sig_scheme": "ed25519",
                "authors": ["abc123"]
            }
        """.trimIndent()
        val expected =
            """{"authors":["abc123"],"kind":"subject","payload":{"typ":"blokove_cisteni","ulice":"Veveří"},""" +
            """"references":{"domain":"traffic_cz"},"sig_scheme":"ed25519",""" +
            """"valid_from":"2026-06-09T06:00:00.000000Z","valid_to":"2026-06-09T10:00:00.000000Z"}"""
        assertEquals(expected, canonical(input))
    }
}
