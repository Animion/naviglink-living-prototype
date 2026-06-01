package cz.naviglink.driver.data

import cz.naviglink.driver.crypto.CanonicalJson
import cz.naviglink.driver.crypto.ContentId
import cz.naviglink.driver.crypto.NaviglinkKeystore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Naviglink HTTP klient — wrappuje endpointy backendu.
 *
 * Použití:
 *   val client = NaviglinkClient(keystore)
 *   val matches = client.queryActive(lon, lat, Instant.now())
 *   client.submitClaim(aboutSubjectId = "naviglink:...", state = "jsem_na_ceste")
 */
class NaviglinkClient(
    private val keystore: NaviglinkKeystore,
    private val baseUrl: String = BACKEND_URL,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val http: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }

    suspend fun health(): Boolean = try {
        http.get("$baseUrl/healthz").status.value in 200..299
    } catch (e: Exception) {
        false
    }

    /** Co platí na souřadnici (lon, lat) v daném čase. */
    suspend fun queryActive(lon: Double, lat: Double, at: Instant): List<SignedSubject> {
        val r = http.get("$baseUrl/query") {
            parameter("lon", lon)
            parameter("lat", lat)
            parameter("at", at.toIsoStringPython())
            parameter("kind", "subject")
        }
        return r.body<QueryResponse>().matches
    }

    /** List všech subjektů konkrétního autora. */
    suspend fun listByAuthor(authorPubHex: String, limit: Int = 50): List<SignedSubject> {
        val r = http.get("$baseUrl/subjects") {
            parameter("author", authorPubHex)
            parameter("kind", "subject")
            parameter("limit", limit)
        }
        return r.body<ListResponse>().subjects
    }

    /**
     * Pošli podepsaný park_snapshot — driver oznamuje "tady jsem zaparkoval".
     *
     * Server uloží snapshot a od této chvíle, když přijde nový subjekt
     * (např. blokové čištění) pokrývající tu polohu, /alerts ho driverovi vrátí.
     *
     * Privacy: posíláme jen jednu polohu v moment parkování, ne kontinuální track.
     *
     * @param lon  longitude
     * @param lat  latitude
     * @param validForHours  jak dlouho snapshot platí (default 12 h — typické noční parkování)
     */
    suspend fun submitParkSnapshot(
        lon: Double,
        lat: Double,
        validForHours: Long = 12,
    ): SubmitResponse {
        val now = Instant.now()
        val validUntil = now.plusSeconds(validForHours * 3600)
        val payload = buildJsonObject {
            put("lon", lon)
            put("lat", lat)
            put("valid_until", validUntil.toIsoStringPython())
        }
        return signAndPost(
            kind = "park_snapshot",
            validFrom = now,
            validTo = validUntil,
            references = emptyMap(),
            payload = payload,
        )
    }

    /**
     * Proaktivní check — server vyhodnotí, jestli na poloze posledního
     * park_snapshot probíhá (nebo se chystá) nějaký subjekt.
     *
     * Pokud `alerts` je prázdné, klient nic neukazuje. Když má počet > 0,
     * worker zobrazí heads-up notifikaci.
     */
    suspend fun getAlerts(): AlertsResponse {
        val r = http.get("$baseUrl/alerts") {
            parameter("author", keystore.publicKeyHex)
        }
        return r.body<AlertsResponse>()
    }

    /**
     * Driver reakce: podepiš claim "jsem na cestě" / "nemohu" o daném subjektu.
     *
     * Tvar claim:
     *   kind = "claim"
     *   references.about = aboutSubjectId
     *   payload = { "reaction": state, "ts": now }
     */
    suspend fun submitClaim(aboutSubjectId: String, state: String): SubmitResponse {
        val now = Instant.now()
        val payload = buildJsonObject {
            put("reaction", state)
            put("ts", now.toIsoStringPython())
        }
        val references = mapOf("about" to aboutSubjectId)
        return signAndPost(
            kind = "claim",
            validFrom = now,
            validTo = now.plusSeconds(60 * 60 * 24),  // platí 24 h
            references = references,
            payload = payload,
        )
    }

    /** Sestaví canonical payload, podepíše, spočítá ID, POSTne na backend. */
    private suspend fun signAndPost(
        kind: String,
        validFrom: Instant,
        validTo: Instant?,
        references: Map<String, String>,
        payload: JsonObject,
    ): SubmitResponse {
        val pubHex = keystore.publicKeyHex
        // Canonical struct musí odpovídat Python SignedSubject.canonical_payload()
        val canonObj = buildJsonObject {
            put("kind", kind)
            put("authors", buildJsonArray { add(pubHex) })
            put("valid_from", validFrom.toIsoStringPython())
            if (validTo != null) put("valid_to", validTo.toIsoStringPython())
            else put("valid_to", JsonPrimitive(null as String?))
            put("references", JsonObject(references.mapValues { JsonPrimitive(it.value) }))
            put("payload", payload)
            put("sig_scheme", "ed25519")
        }
        val canonBytes = CanonicalJson.encodeToBytes(canonObj)
        val signatureB64 = keystore.sign(canonBytes)
        val id = ContentId.compute(canonBytes)

        // Postavit plný SignedSubject pro POST
        val fullObj = buildJsonObject {
            put("id", id)
            put("kind", kind)
            put("authors", buildJsonArray { add(pubHex) })
            put("signatures", buildJsonArray { add(signatureB64) })
            put("valid_from", validFrom.toIsoStringPython())
            if (validTo != null) put("valid_to", validTo.toIsoStringPython())
            put("references", JsonObject(references.mapValues { JsonPrimitive(it.value) }))
            put("payload", payload)
            put("sig_scheme", "ed25519")
        }

        val r = http.post("$baseUrl/subjects") {
            contentType(ContentType.Application.Json)
            setBody(fullObj.toString())
        }
        return r.body<SubmitResponse>()
    }

    companion object {
        const val BACKEND_URL = "https://naviglink-living.onrender.com"
    }
}

/**
 * ISO 8601 UTC s 6-digit microseconds — byte-by-byte stejný formát jako Python:
 *   strftime("%Y-%m-%dT%H:%M:%S.%fZ")
 *
 * Java Instant.toString() produkuje variabilní precision; my potřebujeme
 * konzistentní 6 digits microseconds.
 */
internal fun Instant.toIsoStringPython(): String {
    val seconds = epochSecond
    val nanos = nano
    val micros = nanos / 1000  // odřízneme na microseconds (Python `%f`)
    val zdt = java.time.ZonedDateTime.ofInstant(this, java.time.ZoneOffset.UTC)
    val base = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(zdt)
    return "%s.%06dZ".format(Locale.ROOT, base, micros)
}
