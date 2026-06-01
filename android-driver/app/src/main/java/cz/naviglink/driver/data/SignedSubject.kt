package cz.naviglink.driver.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * SignedSubject — Kotlin reprezentace univerzálního Naviglink primitivu.
 *
 * Pole zarovnaná s Python `naviglink.model.SignedSubject` a JS canonical layout.
 *
 * `payload` a `references` jsou volné JSON struktury (různé pro každý kind).
 * Pro typu "subject" / "claim" je payload doménový dict; pro "schema" je to
 * vlastní severity table; pro "commitment" je to obsah závazku.
 */
@Serializable
data class SignedSubject(
    val id: String,
    val kind: String,
    val authors: List<String>,
    val signatures: List<String>,
    @SerialName("valid_from")
    val validFrom: String,                  // ISO 8601 UTC s 6-digit microseconds
    @SerialName("valid_to")
    val validTo: String? = null,
    val references: Map<String, String> = emptyMap(),
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("sig_scheme")
    val sigScheme: String = "ed25519",
)

/** Response z `GET /query?lon=&lat=&at=`. */
@Serializable
data class QueryResponse(
    val query: JsonElement? = null,
    val matches: List<SignedSubject>,
    val count: Int,
)

/** Response z `GET /subjects?author=&kind=&limit=&offset=`. */
@Serializable
data class ListResponse(
    val filter: JsonElement? = null,
    val count: Int,
    val subjects: List<SignedSubject>,
)

/** Response z `POST /subjects`. */
@Serializable
data class SubmitResponse(
    val id: String,
    val verified: Boolean,
    val stored: Boolean,
)

/**
 * Response z `GET /alerts?author=<pubhex>`.
 *
 * Backend najde latest `park_snapshot` driveru a vrátí seznam subjektů, které
 * pokrývají jeho parkovanou polohu právě teď. `reason` je vyplněn, když není
 * snapshot (nebo vypršel) — pak `alerts` je prázdné a UI nezobrazuje notifikaci.
 */
@Serializable
data class AlertsResponse(
    val author: String,
    @SerialName("park_snapshot")
    val parkSnapshot: SignedSubject? = null,
    @SerialName("checked_at")
    val checkedAt: String? = null,
    val alerts: List<SignedSubject> = emptyList(),
    val count: Int = 0,
    val reason: String? = null,
)
