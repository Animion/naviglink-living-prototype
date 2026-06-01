"""SQLite úložiště pro SignedSubject.

Návrhové volby:
  - JSON-friendly tabulka: každý subjekt jako jeden řádek se serializovaným
    payloadem; references v zvláštním join table pro rychlý lookup
  - Geo dotazy: ukládáme bounding box (min_lon, min_lat, max_lon, max_lat),
    detailní polygon shapely test pak nad subset (post-filter)
  - Append-only: žádný UPDATE/DELETE; revokace je separátní SignedSubject
    typu "revocation" s references["target"] = revoked_id

Pro pilot scale (do 100k subjektů, 1M claimů) SQLite stačí. Pro scale-up
přechod na PostgreSQL + PostGIS — schema je kompatibilní.
"""

from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator, Optional

from shapely.geometry import Polygon, shape

from .model import SignedSubject


SCHEMA = """
CREATE TABLE IF NOT EXISTS subjects (
    id              TEXT PRIMARY KEY,
    kind            TEXT NOT NULL,
    authors         TEXT NOT NULL,    -- JSON array of hex pubkeys
    signatures      TEXT NOT NULL,    -- JSON array of base64 sigs
    valid_from      TEXT NOT NULL,    -- ISO 8601 UTC
    valid_to        TEXT,             -- ISO 8601 UTC nebo NULL
    references_json TEXT NOT NULL,    -- JSON dict
    payload_json    TEXT NOT NULL,    -- JSON dict
    sig_scheme      TEXT NOT NULL DEFAULT 'ed25519',
    -- Bounding box pre rychlý geo prefilter (extracted z payload.geometry)
    bbox_min_lon    REAL,
    bbox_min_lat    REAL,
    bbox_max_lon    REAL,
    bbox_max_lat    REAL,
    -- Audit
    received_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_subjects_kind ON subjects (kind);
CREATE INDEX IF NOT EXISTS idx_subjects_valid_from ON subjects (valid_from);
CREATE INDEX IF NOT EXISTS idx_subjects_valid_to ON subjects (valid_to);
CREATE INDEX IF NOT EXISTS idx_subjects_bbox ON subjects
    (bbox_min_lon, bbox_min_lat, bbox_max_lon, bbox_max_lat);

CREATE TABLE IF NOT EXISTS reference_index (
    subject_id  TEXT NOT NULL,
    role        TEXT NOT NULL,         -- "about", "source", "target", ...
    target_id   TEXT NOT NULL,
    PRIMARY KEY (subject_id, role, target_id),
    FOREIGN KEY (subject_id) REFERENCES subjects(id)
);

CREATE INDEX IF NOT EXISTS idx_ref_target ON reference_index (target_id);
CREATE INDEX IF NOT EXISTS idx_ref_role_target ON reference_index (role, target_id);
"""


class Store:
    """SQLite-backed úložiště pro SignedSubject."""

    def __init__(self, db_path: str | Path = "naviglink.db"):
        self.db_path = str(db_path)
        self._init_db()

    def _init_db(self) -> None:
        with self._connect() as conn:
            conn.executescript(SCHEMA)

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    # ------------------------------------------------------------------------
    # Write
    # ------------------------------------------------------------------------

    def put(self, subject: SignedSubject) -> None:
        """Ulož SignedSubject. Idempotentní — duplicit nahradí (REPLACE).

        Předpokládá, že volající už ověřil podpisy (verify()).
        """
        bbox = _extract_bbox(subject)
        with self._connect() as conn:
            conn.execute(
                """INSERT OR REPLACE INTO subjects
                   (id, kind, authors, signatures, valid_from, valid_to,
                    references_json, payload_json, sig_scheme,
                    bbox_min_lon, bbox_min_lat, bbox_max_lon, bbox_max_lat)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    subject.id,
                    subject.kind,
                    json.dumps(subject.authors),
                    json.dumps(subject.signatures),
                    _iso(subject.valid_from),
                    _iso(subject.valid_to) if subject.valid_to else None,
                    json.dumps(subject.references),
                    json.dumps(subject.payload, default=str),
                    subject.sig_scheme,
                    bbox[0] if bbox else None,
                    bbox[1] if bbox else None,
                    bbox[2] if bbox else None,
                    bbox[3] if bbox else None,
                ),
            )
            # Index references
            conn.execute("DELETE FROM reference_index WHERE subject_id=?",
                          (subject.id,))
            for role, target in subject.references.items():
                conn.execute(
                    """INSERT OR IGNORE INTO reference_index
                       (subject_id, role, target_id) VALUES (?,?,?)""",
                    (subject.id, role, target),
                )

    # ------------------------------------------------------------------------
    # Read
    # ------------------------------------------------------------------------

    def get(self, subject_id: str) -> Optional[SignedSubject]:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM subjects WHERE id=?", (subject_id,)
            ).fetchone()
        return _row_to_subject(row) if row else None

    def find_by_kind(self, kind: str) -> list[SignedSubject]:
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT * FROM subjects WHERE kind=? ORDER BY received_at",
                (kind,),
            ).fetchall()
        return [_row_to_subject(r) for r in rows]

    def list(
        self,
        author: Optional[str] = None,
        kind: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> list[SignedSubject]:
        """List subjektů s volitelnými filtry.

        - author: hex public key autora; SQL hledá podřetězec v JSON pole authors
        - kind: filtr přes kind
        - limit/offset: paginace, default 100/0

        Vrací subjekty seřazené sestupně podle received_at (nejnovější první) —
        konzistentní s UI očekáváním "Mé poslední vyhlášené subjekty".
        """
        clauses = []
        params: list = []
        if author:
            # JSON pole `authors` je seznam stringů; hledáme substring s uvozovkami
            # aby '"abcd"' nepřibližně neshodoval s '"abcdef"'.
            clauses.append("authors LIKE ?")
            params.append(f'%"{author}"%')
        if kind:
            clauses.append("kind = ?")
            params.append(kind)
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        params.extend([limit, offset])

        with self._connect() as conn:
            rows = conn.execute(
                f"SELECT * FROM subjects{where} "
                f"ORDER BY received_at DESC LIMIT ? OFFSET ?",
                params,
            ).fetchall()
        return [_row_to_subject(r) for r in rows]

    def find_referencing(self, target_id: str,
                          role: Optional[str] = None) -> list[SignedSubject]:
        """Najdi subjekty, které odkazují na target_id (volitelně v dané roli)."""
        with self._connect() as conn:
            if role:
                rows = conn.execute(
                    """SELECT s.* FROM subjects s
                       JOIN reference_index r ON r.subject_id = s.id
                       WHERE r.target_id=? AND r.role=?
                       ORDER BY s.received_at""",
                    (target_id, role),
                ).fetchall()
            else:
                rows = conn.execute(
                    """SELECT s.* FROM subjects s
                       JOIN reference_index r ON r.subject_id = s.id
                       WHERE r.target_id=?
                       ORDER BY s.received_at""",
                    (target_id,),
                ).fetchall()
        return [_row_to_subject(r) for r in rows]

    def query_active_at(
        self,
        lon: float,
        lat: float,
        at: datetime,
        kind: Optional[str] = None,
    ) -> list[SignedSubject]:
        """Co platí na souřadnici (lon, lat) v čase `at`.

        Postup:
          1) SQL prefilter: aktivní časový rozsah + bbox obsahuje bod
          2) Python post-filter: shapely polygon contains point (přesný test)
          3) Filter revokovaných (přes reference_index where target = this.id
             and revocation.valid_from <= at)
        """
        at_iso = _iso(at)
        bbox_clause = "(bbox_min_lon IS NULL OR (bbox_min_lon <= ? AND bbox_max_lon >= ? AND bbox_min_lat <= ? AND bbox_max_lat >= ?))"
        time_clause = "valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)"
        kind_clause = ""
        params = [lon, lon, lat, lat, at_iso, at_iso]
        if kind:
            kind_clause = " AND kind = ?"
            params.append(kind)
        with self._connect() as conn:
            rows = conn.execute(
                f"SELECT * FROM subjects WHERE {bbox_clause} AND {time_clause}{kind_clause}",
                params,
            ).fetchall()

        candidates = [_row_to_subject(r) for r in rows]
        # Post-filter: exact geometry match
        matched = []
        for s in candidates:
            if _point_in_subject_geometry(s, lon, lat):
                matched.append(s)

        # Filter revoked
        revoked_ids = self._revoked_ids_at(matched, at)
        return [s for s in matched if s.id not in revoked_ids]

    def _revoked_ids_at(self, subjects: list[SignedSubject],
                         at: datetime) -> set[str]:
        """Najdi IDs, která jsou revokovaná v čase `at`."""
        if not subjects:
            return set()
        at_iso = _iso(at)
        ids = [s.id for s in subjects]
        placeholders = ",".join("?" * len(ids))
        with self._connect() as conn:
            rows = conn.execute(
                f"""SELECT r.target_id FROM reference_index r
                    JOIN subjects s ON s.id = r.subject_id
                    WHERE s.kind = 'revocation'
                      AND r.role = 'target'
                      AND r.target_id IN ({placeholders})
                      AND s.valid_from <= ?""",
                (*ids, at_iso),
            ).fetchall()
        return {r["target_id"] for r in rows}

    # ------------------------------------------------------------------------
    # Audit
    # ------------------------------------------------------------------------

    def audit_log_for(self, subject_id: str) -> list[SignedSubject]:
        """Vše, co subject_id zmiňuje — jako subjekt sám nebo přes references."""
        with self._connect() as conn:
            rows = conn.execute(
                """SELECT * FROM subjects
                   WHERE id=?
                   UNION
                   SELECT s.* FROM subjects s
                   JOIN reference_index r ON r.subject_id = s.id
                   WHERE r.target_id=?
                   ORDER BY received_at""",
                (subject_id, subject_id),
            ).fetchall()
        return [_row_to_subject(r) for r in rows]


# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------

def _iso(dt: datetime) -> str:
    """ISO 8601 UTC string."""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def _parse_iso(s: str) -> datetime:
    """Parse ISO string back to datetime."""
    # Zachovává naše .strftime("%Y-%m-%dT%H:%M:%S.%fZ") formát
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)


def _extract_bbox(subject: SignedSubject) -> Optional[tuple[float, float, float, float]]:
    """Extrahuj bounding box z payload.geometry (GeoJSON polygon)."""
    geom = subject.payload.get("geometry")
    if not geom:
        return None
    try:
        poly = shape(geom)
        min_x, min_y, max_x, max_y = poly.bounds
        return (min_x, min_y, max_x, max_y)
    except Exception:
        return None


def _point_in_subject_geometry(subject: SignedSubject, lon: float, lat: float) -> bool:
    """Test, zda bod (lon, lat) leží uvnitř polygonu subject.payload.geometry.

    Pokud subjekt nemá geometrii, vrací False (geo-temporal query
    je definováno jen pro geo-anchored subjekty)."""
    geom = subject.payload.get("geometry")
    if not geom:
        return False
    try:
        from shapely.geometry import Point
        poly = shape(geom)
        return poly.contains(Point(lon, lat))
    except Exception:
        return False


def _row_to_subject(row: sqlite3.Row) -> SignedSubject:
    return SignedSubject(
        id=row["id"],
        kind=row["kind"],
        authors=json.loads(row["authors"]),
        signatures=json.loads(row["signatures"]),
        valid_from=_parse_iso(row["valid_from"]),
        valid_to=_parse_iso(row["valid_to"]) if row["valid_to"] else None,
        references=json.loads(row["references_json"]),
        payload=json.loads(row["payload_json"]),
        sig_scheme=row["sig_scheme"],
    )
