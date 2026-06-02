"""FastAPI app — minimal Naviglink HTTP API.

Endpointy:
  GET  /healthz                       — basic health check
  POST /subjects                      — přijmi podepsaný SignedSubject
  GET  /subjects/{id}                 — najdi podle ID
  GET  /subjects?author=&kind=        — list s filtry
  GET  /query?lon=&lat=&at=&kind=    — co platí na souřadnici v čase
  GET  /alerts?author=<pubhex>        — proaktivně: subjekty na poloze
                                        latest park_snapshot daného driveru
  GET  /audit/{id}                    — vše, co subject_id zmiňuje

Žádný auth middleware — autentizace je v podpisech samotných SignedSubject.
CORS otevřený (pilot scope; production by mělo per-origin whitelist).
"""

from __future__ import annotations

import os
from datetime import datetime, timezone
from typing import Optional

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .model import SignedSubject
from .identifier import compute_id, verify_id


# ---------------------------------------------------------------------------
# App + storage (SQLite pro lokální dev, PostgreSQL pro Render produkční)
# ---------------------------------------------------------------------------

DATABASE_URL = os.environ.get("DATABASE_URL")

if DATABASE_URL:
    from .store_postgres import PostgresStore
    store = PostgresStore(DATABASE_URL)
    _STORAGE_BACKEND = "postgresql"
else:
    from .store import Store
    DB_PATH = os.environ.get("NAVIGLINK_DB", "naviglink.db")
    store = Store(DB_PATH)
    _STORAGE_BACKEND = "sqlite"

app = FastAPI(
    title="Naviglink",
    description="Living prototype — vrstva 0 (SignedSubject) přes HTTP.",
    version="0.1.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],   # pilot scope; produkce per-origin
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def no_cache_for_reads(request, call_next):
    """Zabrání cachování GET odpovědí — ani v browseru, ani na edge.

    Bez tohoto: revokace vyhlášená v jednom okně se nepromítne do druhého,
    dokud uživatel nevyčistí cache (Render edge / Cloudflare / browser cache
    všechny mohou držet starou /subjects odpověď). Pro pilot drak prioritou
    je čerstvost dat, ne cache hit rate.
    """
    response = await call_next(request)
    if request.method == "GET":
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    return response


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok", "version": app.version, "storage": _STORAGE_BACKEND}


class SubmitResponse(BaseModel):
    id: str
    verified: bool
    stored: bool


@app.post("/subjects", response_model=SubmitResponse)
def submit_subject(subject: SignedSubject) -> SubmitResponse:
    """Přijmi podepsaný SignedSubject.

    Postup:
      1) Ověř, že ID je content-addressed (= hash payloadu)
      2) Ověř všechny podpisy (verify())
      3) Ulož do store
    """
    # 1) Content-addressed ID check
    expected_id = compute_id(subject.canonical_payload())
    if subject.id != expected_id:
        raise HTTPException(
            status_code=400,
            detail=f"Subject ID mismatch. Expected {expected_id}, got {subject.id}",
        )

    # 2) Signature verification
    if not subject.verify():
        raise HTTPException(
            status_code=400,
            detail="Signature verification failed.",
        )

    # 3) Store
    store.put(subject)
    return SubmitResponse(id=subject.id, verified=True, stored=True)


@app.get("/subjects/{subject_id}", response_model=SignedSubject)
def get_subject(subject_id: str) -> SignedSubject:
    s = store.get(subject_id)
    if not s:
        raise HTTPException(status_code=404, detail="Subject not found")
    return s


@app.get("/subjects")
def list_subjects(
    author: Optional[str] = Query(None, description="Hex public key autora"),
    kind: Optional[str] = Query(None, description="Filter by kind"),
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
) -> dict:
    """List subjektů s volitelnými filtry.

    Použití:
      - GET /subjects?author=<hex>   → všechny subjekty autora (např. seznam
        vyhlášených blokových čištění magistrátu pro overlay v admin mapě)
      - GET /subjects?kind=subject   → všechny subjekty typu "subject"
      - GET /subjects?kind=claim&author=<hex>  → reakce konkrétního řidiče

    Vrací paginovaný seznam seřazený sestupně podle času přijetí (nejnovější první).
    """
    subjects = store.list(author=author, kind=kind, limit=limit, offset=offset)
    return {
        "filter": {"author": author, "kind": kind, "limit": limit, "offset": offset},
        "count": len(subjects),
        "subjects": [s.model_dump(mode="json") for s in subjects],
    }


@app.get("/query")
def query_active(
    lon: float = Query(..., description="GPS longitude (WGS84)"),
    lat: float = Query(..., description="GPS latitude (WGS84)"),
    at: Optional[datetime] = Query(
        None, description="ISO 8601; defaults to server now (UTC)"
    ),
    kind: Optional[str] = Query(None, description="Filter by kind"),
) -> dict:
    """Co platí na souřadnici (lon, lat) v čase `at`."""
    if at is None:
        at = datetime.now(timezone.utc)
    elif at.tzinfo is None:
        at = at.replace(tzinfo=timezone.utc)

    subjects = store.query_active_at(lon, lat, at, kind=kind)
    return {
        "query": {
            "lon": lon,
            "lat": lat,
            "at": at.isoformat(),
            "kind": kind,
        },
        "matches": [s.model_dump(mode="json") for s in subjects],
        "count": len(subjects),
    }


@app.get("/alerts")
def alerts_for_driver(
    author: str = Query(..., description="Hex public key driveru"),
) -> dict:
    """Proaktivní notifikace: subjekty na poloze posledního park_snapshot.

    Postup:
      1) Najdi latest `park_snapshot` od `author` (zaparkovaná poloha driveru)
      2) Pokud existuje a `valid_until` v payloadu ještě nevypršel:
         → query_active_at(lon, lat, now, kind="subject")
      3) Vrať seznam aktivních (nebo právě nastávajících) subjektů

    Driver app to volá periodicky (WorkManager ~30 min) a pokud `alerts` má
    nenulový obsah, zobrazí heads-up notifikaci.

    Privacy: driver pošle polohu jen když zaparkuje (explicit consent), ne
    kontinuální tracking. Server zná "kde je auto teď", ne kudy jezdilo.
    """
    snapshots = store.list(author=author, kind="park_snapshot", limit=1)
    if not snapshots:
        return {
            "author": author,
            "park_snapshot": None,
            "alerts": [],
            "reason": "no_park_snapshot",
        }

    ps = snapshots[0]
    payload = ps.payload or {}
    lon = payload.get("lon")
    lat = payload.get("lat")

    if lon is None or lat is None:
        return {
            "author": author,
            "park_snapshot": ps.model_dump(mode="json"),
            "alerts": [],
            "reason": "park_snapshot_missing_coords",
        }

    now = datetime.now(timezone.utc)

    valid_until_str = payload.get("valid_until")
    if valid_until_str:
        try:
            vu = datetime.fromisoformat(str(valid_until_str).replace("Z", "+00:00"))
            if vu.tzinfo is None:
                vu = vu.replace(tzinfo=timezone.utc)
            if now > vu:
                return {
                    "author": author,
                    "park_snapshot": ps.model_dump(mode="json"),
                    "alerts": [],
                    "reason": "park_snapshot_expired",
                    "checked_at": now.isoformat(),
                }
        except (ValueError, TypeError):
            pass

    matches = store.query_active_at(float(lon), float(lat), now, kind="subject")
    return {
        "author": author,
        "park_snapshot": ps.model_dump(mode="json"),
        "checked_at": now.isoformat(),
        "alerts": [s.model_dump(mode="json") for s in matches],
        "count": len(matches),
    }


@app.get("/audit/{subject_id}")
def audit_log(subject_id: str) -> dict:
    """Vše, co subject_id zmiňuje — jako subjekt sám nebo přes references."""
    entries = store.audit_log_for(subject_id)
    return {
        "subject_id": subject_id,
        "entries": [s.model_dump(mode="json") for s in entries],
        "count": len(entries),
    }


# ---------------------------------------------------------------------------
# Entry point pro uvicorn (Render.com kompatibilní)
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("PORT", "8000"))
    uvicorn.run("naviglink.app:app", host="0.0.0.0", port=port, reload=True)
