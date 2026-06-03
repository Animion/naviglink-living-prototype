"""Server-Sent Events (SSE) real-time broadcast pro driver app.

Architektura:
  1) Driver app otevře GET /events?author=<pubhex>
  2) Server vytvoří asyncio.Queue, registruje ji v `_subscribers[author_hex]`
  3) Stream drží otevřený, posílá:
       a) Po připojení: aktuální stav (current_subjects pokrývající park_snapshot)
       b) Periodicky každých 25 s: keepalive comment (`: ping\n\n`)
       c) Při novém subjektu na POST /subjects: ihned event
  4) Při disconnect (close / network drop) se queue odstraní

Omezení: in-memory pub-sub funguje jen pro **jednu instanci backendu**. Pokud
Render škáluje na víc instances, driver A na instance 1 a magistrát POST na
instance 2 → broadcast nedoputuje. Pro Render free tier (1 instance) je to OK.
Pro produkci přejít na Redis pub-sub nebo PostgreSQL LISTEN/NOTIFY.
"""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone
from typing import TYPE_CHECKING, Optional

if TYPE_CHECKING:
    from .model import SignedSubject

logger = logging.getLogger(__name__)

# Globální registry: author_hex -> set of queues (driver může mít víc connections)
# Použití set umožňuje multi-device: stejný klíč v telefonu i tabletu dostane oba.
_subscribers: dict[str, set[asyncio.Queue]] = {}

# Event TTL — broker neudržuje historii, jen real-time. Sotva budeme potřebovat
# event replay (driver by místo toho použil polling fallback).


def subscribe(author_hex: str) -> asyncio.Queue:
    """Vytvoří novou queue pro daného autora a vrátí ji. Volající ji musí
    `unsubscribe(author_hex, queue)` po skončení streamu."""
    q: asyncio.Queue = asyncio.Queue(maxsize=64)  # bound queue → backpressure
    _subscribers.setdefault(author_hex, set()).add(q)
    logger.info("SSE subscribe author=%s total_subs=%d", author_hex[:12], _count_for(author_hex))
    return q


def unsubscribe(author_hex: str, queue: asyncio.Queue) -> None:
    queues = _subscribers.get(author_hex)
    if queues:
        queues.discard(queue)
        if not queues:
            _subscribers.pop(author_hex, None)
    logger.info("SSE unsubscribe author=%s remaining=%d", author_hex[:12], _count_for(author_hex))


def _count_for(author_hex: str) -> int:
    return len(_subscribers.get(author_hex, set()))


async def publish(author_hex: str, event: dict) -> int:
    """Pošle event všem aktivním queue tohoto autora. Vrátí počet doručených."""
    queues = _subscribers.get(author_hex)
    if not queues:
        return 0
    sent = 0
    # Snapshot — set se může měnit (unsubscribe v jiné task)
    for q in list(queues):
        try:
            q.put_nowait(event)
            sent += 1
        except asyncio.QueueFull:
            logger.warning("SSE queue full for author=%s, event dropped", author_hex[:12])
    return sent


def stats() -> dict:
    """Pro /healthz nebo debug — kolik je aktivních connections."""
    return {
        "authors": len(_subscribers),
        "total_streams": sum(len(qs) for qs in _subscribers.values()),
    }


# ---------------------------------------------------------------------------
# Broadcast: při novém subjektu najít drivery uvnitř polygonu a publish event
# ---------------------------------------------------------------------------

async def broadcast_alerts_for_new_subject(store, subject: "SignedSubject") -> dict:
    """Při POST nového subjektu typu "subject" projde park_snapshots a publish
    event všem driverům, jejichž poloha leží uvnitř polygonu nového subjektu.

    Vrací dict pro logging:
      {"park_snapshots_checked": N, "drivers_notified": M, "events_published": K}
    """
    if subject.kind != "subject":
        return {"skipped": "not_a_subject"}

    payload = subject.payload or {}
    geom = payload.get("geometry")
    if not geom or geom.get("type") != "Polygon":
        return {"skipped": "no_polygon"}

    coords = geom.get("coordinates", [[]])[0]
    if len(coords) < 3:
        return {"skipped": "polygon_too_small"}

    park_snapshots = store.list(kind="park_snapshot", limit=500)
    ulice = payload.get("ulice", "(neuvedeno)")
    now = datetime.now(timezone.utc)

    drivers: dict[str, str] = {}  # author_hex → ulice
    for ps in park_snapshots:
        ps_payload = ps.payload or {}
        lon = ps_payload.get("lon")
        lat = ps_payload.get("lat")
        if lon is None or lat is None:
            continue
        # Validity park_snapshotu
        vu_str = ps_payload.get("valid_until")
        if vu_str:
            try:
                vu = datetime.fromisoformat(str(vu_str).replace("Z", "+00:00"))
                if vu.tzinfo is None:
                    vu = vu.replace(tzinfo=timezone.utc)
                if now > vu:
                    continue
            except (ValueError, TypeError):
                pass
        if _point_in_ring(float(lon), float(lat), coords):
            for author in ps.authors:
                drivers[author] = ulice

    events_published = 0
    event_payload = {
        "type": "alert",
        "subject_id": subject.id,
        "ulice": ulice,
        "valid_from": (
            subject.valid_from.isoformat() if hasattr(subject.valid_from, "isoformat")
            else str(subject.valid_from)
        ),
        "valid_to": (
            subject.valid_to.isoformat() if subject.valid_to and hasattr(subject.valid_to, "isoformat")
            else str(subject.valid_to or "")
        ),
    }

    # POST handler je async, voláme přímo await publish()
    for author_hex in drivers.keys():
        try:
            sent = await publish(author_hex, event_payload)
            events_published += sent
        except Exception as e:  # noqa: BLE001
            logger.warning("Broadcast to author=%s failed: %s", author_hex[:12], e)

    return {
        "park_snapshots_checked": len(park_snapshots),
        "drivers_notified": len(drivers),
        "events_published": events_published,
    }


def _point_in_ring(lon: float, lat: float, ring: list[list[float]]) -> bool:
    """Ray-casting point-in-polygon. ring je list [lon, lat] souřadnic."""
    inside = False
    n = len(ring)
    j = n - 1
    for i in range(n):
        xi, yi = ring[i][0], ring[i][1]
        xj, yj = ring[j][0], ring[j][1]
        if ((yi > lat) != (yj > lat)) and (
            lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi
        ):
            inside = not inside
        j = i
    return inside


# ---------------------------------------------------------------------------
# SSE stream generator
# ---------------------------------------------------------------------------

async def event_stream(author_hex: str):
    """Async generator pro StreamingResponse. Yieldne SSE-formatted bytes."""
    queue = subscribe(author_hex)
    try:
        # Hello event — klient pozná, že connection je live
        yield _format_sse({"type": "hello", "author": author_hex[:12]})

        keepalive_interval = 25.0  # sec
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=keepalive_interval)
                yield _format_sse(event)
            except asyncio.TimeoutError:
                # Keepalive comment — SSE servery a proxy potřebují periodický traffic
                yield b": ping\n\n"
            except asyncio.CancelledError:
                break
    finally:
        unsubscribe(author_hex, queue)


def _format_sse(data: dict) -> bytes:
    """Formát: `data: <json>\\n\\n` — standard SSE event bez `event:` typu."""
    return f"data: {json.dumps(data, ensure_ascii=False)}\n\n".encode("utf-8")
