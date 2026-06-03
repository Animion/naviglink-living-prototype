"""Importer Waze Alerts z data.brno.cz.

Architektura (validovaná s autorem v rozhodnutí A.1/B.1/C/D):

  A.1 Proxy attestation. Importer má vlastní Ed25519 klíč ("Naviglink Brno
      Importer") a podepisuje subjekty sám sebou. V `references.source` je URL
      feedu a `references.source_id` je Waze UUID — to říká driverovi/občanovi,
      že subjekt **NEvyhlásil magistrát**, ale Naviglink importer ho převzal
      z veřejného feedu. Striktně whitepaper-konformní cesta (data.brno.cz
      s vlastním did:web:data.brno.cz) je nad rámec tohoto pilotu.

  B.1 Manual trigger. Endpoint `POST /admin/import/brno-waze` v app.py.
      Cron job je v plánu (B.2), zatím ne.

  C   Heuristika valid_to per Waze type — feed nezadává konec platnosti,
      jen pubMillis. Tabulka v WAZE_TTL_HOURS.

  D   Polygon kolem bodu — oktagon s rádiem ~80 m. Hrubá aproximace, pro
      pilot stačí. Produkční verze by matchovala proti OSM way geometry.
"""

from __future__ import annotations

import logging
import math
import os
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

import httpx
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

from .model import SignedSubject, canonical_json
from .identifier import compute_id

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Konfigurace
# ---------------------------------------------------------------------------

# Feed obsahuje archiv let zpět — bez WHERE bychom importovali staré expirované
# alerty, které nikdy nepřejdou do public dashboardu (`/upcoming` je filtruje).
# Filtrujeme aktuální (posledních 24 h) a sortujeme desc pro deterministické pořadí.
WAZE_FEED_URL_TEMPLATE = (
    "https://gis.brno.cz/ags1/rest/services/Hosted/WazeAlerts/FeatureServer/0/query"
    "?where=pubMillis%3E{since_ms}"
    "&outFields=*&outSR=4326&f=json"
    "&resultRecordCount=300"
    "&orderByFields=pubMillis+DESC"
)
RECENT_HOURS = 24

# Heuristika valid_to (hodiny od pubMillis) — feed nezadává konec platnosti.
WAZE_TTL_HOURS: dict[str, float] = {
    "ROAD_CLOSED": 24.0,
    "HAZARD": 4.0,
    "ACCIDENT": 2.0,
    "JAM": 1.0,
}
WAZE_TTL_DEFAULT_HOURS = 6.0

# Octagon kolem bodu — rádius v desetinných stupních. 0.0008° ~ 80 m v Brně.
OCTAGON_RADIUS_DEG = 0.0008

# Source URL v references — pro transparenci
SOURCE_URL = "https://gis.brno.cz/ags1/rest/services/Hosted/WazeAlerts/FeatureServer/0"


# ---------------------------------------------------------------------------
# Importer keypair
# ---------------------------------------------------------------------------

class ImporterKeyring:
    """Drží Ed25519 klíč pro importer. Priorita: env var → DB app_state → vygenerovat a uložit."""

    APP_STATE_KEY = "importer_brno_waze_priv_hex"

    def __init__(
        self,
        store=None,
        env_var: str = "NAVIGLINK_IMPORTER_BRNO_PRIV_HEX",
    ):
        priv_hex = os.environ.get(env_var)
        source = "env"
        if not priv_hex and store is not None:
            priv_hex = store.app_state_get(self.APP_STATE_KEY)
            source = "db" if priv_hex else None
        if not priv_hex:
            # Vygeneruj a (pokud máme store) ulož do DB pro persistenci.
            priv_bytes = secrets.token_bytes(32)
            priv_hex = priv_bytes.hex()
            source = "generated"
            if store is not None:
                store.app_state_set(self.APP_STATE_KEY, priv_hex)
                source = "generated+db"

        self.priv = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(priv_hex))
        pub_hex = self.priv.public_key().public_bytes_raw().hex()
        logger.info("Importer key loaded (source=%s) pub=%s", source, pub_hex[:16] + "…")

    @property
    def pub_hex(self) -> str:
        return self.priv.public_key().public_bytes_raw().hex()

    def sign(self, canonical_bytes: bytes) -> bytes:
        return self.priv.sign(canonical_bytes)


# Singleton — inicializován při prvním requestu
_keyring: Optional[ImporterKeyring] = None


def get_keyring(store=None) -> ImporterKeyring:
    """Vrátí (a inicializuje) keyring. Při prvním volání musí být store dodán,
    aby klíč mohl být persistován v DB."""
    global _keyring
    if _keyring is None:
        _keyring = ImporterKeyring(store=store)
    return _keyring


# ---------------------------------------------------------------------------
# Polygon kolem bodu — oktagon
# ---------------------------------------------------------------------------

def point_to_octagon(lon: float, lat: float, radius_deg: float = OCTAGON_RADIUS_DEG) -> list[list[float]]:
    """Vytvoří 8-úhelník kolem bodu (lon, lat). Vrátí list [lon, lat] dvojic, uzavřený.

    Longitude se opravuje o cos(latitude), aby reálná velikost ve směru východ-západ
    odpovídala v metrech délce ve směru sever-jih.
    """
    lat_factor = math.cos(math.radians(lat))
    points = []
    for i in range(8):
        angle = 2 * math.pi * i / 8
        dx = radius_deg * math.cos(angle) / lat_factor
        dy = radius_deg * math.sin(angle)
        points.append([lon + dx, lat + dy])
    points.append(points[0])  # uzavření ringu (GeoJSON pravidlo)
    return points


# ---------------------------------------------------------------------------
# Mapování Waze feature → SignedSubject
# ---------------------------------------------------------------------------

def feature_to_subject(feature: dict, keyring: ImporterKeyring) -> SignedSubject:
    """Konvertuje jeden Waze feature na podepsaný SignedSubject."""
    attrs = feature.get("attributes", {})

    waze_uuid = attrs.get("uuid") or attrs.get("globalid")
    if not waze_uuid:
        raise ValueError("Feature without uuid/globalid")

    pub_millis = attrs.get("pubMillis")
    if not pub_millis:
        raise ValueError("Feature without pubMillis")

    valid_from = datetime.fromtimestamp(pub_millis / 1000.0, tz=timezone.utc)
    waze_type = attrs.get("type", "UNKNOWN")
    ttl_hours = WAZE_TTL_HOURS.get(waze_type, WAZE_TTL_DEFAULT_HOURS)
    valid_to = valid_from + timedelta(hours=ttl_hours)

    lon = attrs.get("longitude") or feature.get("geometry", {}).get("x")
    lat = attrs.get("latitude") or feature.get("geometry", {}).get("y")
    if lon is None or lat is None:
        raise ValueError("Feature without coordinates")

    polygon = point_to_octagon(float(lon), float(lat))

    payload = {
        "typ": "traffic_restriction",
        "ulice": attrs.get("street") or "(neuvedeno)",
        "geometry": {"type": "Polygon", "coordinates": [polygon]},
        "waze_type": waze_type,
        "waze_subtype": attrs.get("subtype") or "",
        "city": attrs.get("city") or "",
        "confidence": attrs.get("confidence"),
        "reliability": attrs.get("reliability"),
        "thumbs_up": attrs.get("nThumbsUp"),
        "road_type": attrs.get("roadType"),
        "description": attrs.get("reportDescription") or "",
        # Attribution dle požadavku data.brno.cz: "je nutné citovat WAZE
        # a City of Brno jako zdroj". Drží se v payloadu, takže klient ji
        # vidí přímo bez nutnosti rozbalit references.
        "attribution": "© WAZE Mobile Ltd. via data.brno.cz (Statutární město Brno)",
    }

    references = {
        "domain": "traffic_cz",
        "source": SOURCE_URL,
        "source_id": str(waze_uuid),
        "source_program": "WAZE for Cities",
        "source_license": "free reuse with attribution",
    }

    # Build SignedSubject — sign + compute_id
    pub_hex = keyring.pub_hex

    canonical_dict = {
        "kind": "subject",
        "authors": [pub_hex],
        "valid_from": valid_from,
        "valid_to": valid_to,
        "references": references,
        "payload": payload,
        "sig_scheme": "ed25519",
    }
    canon_bytes = canonical_json(canonical_dict)
    sig_bytes = keyring.sign(canon_bytes)

    import base64
    sig_b64 = base64.b64encode(sig_bytes).decode("ascii")
    subject_id = compute_id(canon_bytes)

    return SignedSubject(
        id=subject_id,
        kind="subject",
        authors=[pub_hex],
        signatures=[sig_b64],
        valid_from=valid_from,
        valid_to=valid_to,
        references=references,
        payload=payload,
        sig_scheme="ed25519",
    )


# ---------------------------------------------------------------------------
# Pull + sync
# ---------------------------------------------------------------------------

async def fetch_waze_features() -> list[dict]:
    """Pullne aktuální Waze alerty z data.brno.cz (jen z posledních RECENT_HOURS hodin)."""
    import time
    t0 = time.time()
    since_dt = datetime.now(timezone.utc) - timedelta(hours=RECENT_HOURS)
    since_ms = int(since_dt.timestamp() * 1000)
    url = WAZE_FEED_URL_TEMPLATE.format(since_ms=since_ms)
    print(f"[importer] fetching alerts since {since_dt.isoformat()}", flush=True)
    print(f"[importer] url: {url}", flush=True)
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.get(url)
        r.raise_for_status()
        data = r.json()
    features = data.get("features", [])
    print(f"[importer] fetched {len(features)} features in {time.time()-t0:.2f}s", flush=True)
    return features


def find_existing_subject_for_uuid(store, waze_uuid: str) -> Optional[SignedSubject]:
    """Najdi subjekt, který referencuje daný Waze UUID jako source_id.

    Reference_index má řádek (subject_id, "source_id", "<uuid>"), takže
    `find_referencing(target_id=uuid, role="source_id")` to vrátí.
    """
    candidates = store.find_referencing(target_id=waze_uuid, role="source_id")
    # Mohou existovat víc kandidátů, pokud importer běžel víckrát s drobnou
    # změnou v payloadu (např. update confidence). Vrátíme jednoho (libovolného).
    return candidates[0] if candidates else None


async def import_brno_waze(store) -> dict[str, Any]:
    """Hlavní entrypoint — fetchne feed, naimportuje, revokuje zmizelé.

    Vrací summary dict pro UI/log:
      {imported: N, skipped: M, errors: list, fetched: total}
    """
    import time
    t_start = time.time()
    print("[importer] === START import_brno_waze ===", flush=True)

    print("[importer] step 1: get keyring", flush=True)
    keyring = get_keyring(store=store)
    print(f"[importer] keyring ready, pub={keyring.pub_hex[:16]}…", flush=True)

    print("[importer] step 2: fetch features", flush=True)
    features = await fetch_waze_features()

    print(f"[importer] step 3: process {len(features)} features sequentially", flush=True)

    imported = 0
    skipped = 0
    errors: list[str] = []
    seen_uuids: set[str] = set()

    for idx, f in enumerate(features):
        if idx % 10 == 0:
            elapsed = time.time() - t_start
            print(
                f"[importer]   progress {idx}/{len(features)} "
                f"(imported={imported}, skipped={skipped}, errors={len(errors)}, "
                f"elapsed={elapsed:.1f}s)",
                flush=True,
            )
        try:
            attrs = f.get("attributes", {})
            waze_uuid = str(attrs.get("uuid") or attrs.get("globalid") or "")
            if not waze_uuid:
                errors.append("feature without uuid")
                continue
            seen_uuids.add(waze_uuid)

            t_find = time.time()
            existing = find_existing_subject_for_uuid(store, waze_uuid)
            t_find = time.time() - t_find
            if t_find > 1.0:
                print(f"[importer]   slow find_referencing: {t_find:.2f}s for {waze_uuid}", flush=True)

            if existing:
                skipped += 1
                continue

            subject = feature_to_subject(f, keyring)

            t_put = time.time()
            store.put(subject)
            t_put = time.time() - t_put
            if t_put > 1.0:
                print(f"[importer]   slow put: {t_put:.2f}s for {waze_uuid}", flush=True)

            imported += 1
        except Exception as e:  # noqa: BLE001
            import traceback
            errors.append(f"{f.get('attributes', {}).get('uuid', '?')}: {e}")
            print(f"[importer] FAIL idx={idx} err={e}", flush=True)
            print(traceback.format_exc(), flush=True)

    total_elapsed = time.time() - t_start
    print(
        f"[importer] === DONE in {total_elapsed:.1f}s: "
        f"fetched={len(features)} imported={imported} skipped={skipped} errors={len(errors)} ===",
        flush=True,
    )

    return {
        "fetched": len(features),
        "imported": imported,
        "skipped": skipped,
        "errors": errors[:20],
        "importer_pub_hex": keyring.pub_hex[:16] + "…",
        "elapsed_seconds": round(total_elapsed, 2),
    }
