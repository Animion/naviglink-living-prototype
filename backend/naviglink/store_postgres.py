"""PostgreSQL backend úložiště pro SignedSubject — pro produkční Render deploy.

Stejné API jako `store.py` (SQLite), volené přes env var `DATABASE_URL`:
  - není-li nastaveno → SQLite (lokální dev, tests)
  - je-li nastaveno → PostgreSQL přes psycopg3

Schema je téměř identické se SQLite verzí; rozdíly:
  - placeholders `%s` místo `?`
  - `INSERT ... ON CONFLICT (id) DO UPDATE` místo `INSERT OR REPLACE`
  - `TIMESTAMPTZ` místo TEXT pro časové sloupce (lepší indexace, range queries)
  - `JSONB` místo TEXT pro authors/payload (nativní JSON dotazy možné v budoucnu)

Pro pilot scale (do 100 tis. subjektů, 1M claimů) tento adapter nevyžaduje
PostGIS — bounding box prefilter v plain SQL je dostačující.
"""

from __future__ import annotations

import json
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Iterator, Optional

import psycopg
from shapely.geometry import Point, shape

from .model import SignedSubject


SCHEMA = """
CREATE TABLE IF NOT EXISTS subjects (
    id              TEXT PRIMARY KEY,
    kind            TEXT NOT NULL,
    authors         JSONB NOT NULL,
    signatures      JSONB NOT NULL,
    valid_from      TIMESTAMPTZ NOT NULL,
    valid_to        TIMESTAMPTZ,
    references_json JSONB NOT NULL,
    payload_json    JSONB NOT NULL,
    sig_scheme      TEXT NOT NULL DEFAULT 'ed25519',
    bbox_min_lon    DOUBLE PRECISION,
    bbox_min_lat    DOUBLE PRECISION,
    bbox_max_lon    DOUBLE PRECISION,
    bbox_max_lat    DOUBLE PRECISION,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_subjects_kind ON subjects (kind);
CREATE INDEX IF NOT EXISTS idx_subjects_valid_from ON subjects (valid_from);
CREATE INDEX IF NOT EXISTS idx_subjects_valid_to ON subjects (valid_to);
CREATE INDEX IF NOT EXISTS idx_subjects_bbox ON subjects
    (bbox_min_lon, bbox_min_lat, bbox_max_lon, bbox_max_lat);

CREATE TABLE IF NOT EXISTS reference_index (
    subject_id  TEXT NOT NULL,
    role        TEXT NOT NULL,
    target_id   TEXT NOT NULL,
    PRIMARY KEY (subject_id, role, target_id),
    FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ref_target ON reference_index (target_id);
CREATE INDEX IF NOT EXISTS idx_ref_role_target ON reference_index (role, target_id);

CREATE TABLE IF NOT EXISTS fcm_tokens (
    author_hex   TEXT NOT NULL,
    fcm_token    TEXT NOT NULL,
    platform     TEXT NOT NULL DEFAULT 'android',
    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (author_hex, fcm_token)
);

CREATE INDEX IF NOT EXISTS idx_fcm_author ON fcm_tokens (author_hex);
"""


class PostgresStore:
    """PostgreSQL-backed úložiště pro SignedSubject."""

    def __init__(self, dsn: str):
        self.dsn = dsn
        self._init_db()

    def _init_db(self) -> None:
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(SCHEMA)

    @contextmanager
    def _connect(self) -> Iterator[psycopg.Connection]:
        conn = psycopg.connect(self.dsn, autocommit=False)
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    # ------------------------------------------------------------------------
    # Write
    # ------------------------------------------------------------------------

    def put(self, subject: SignedSubject) -> None:
        bbox = _extract_bbox(subject)
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """INSERT INTO subjects
                       (id, kind, authors, signatures, valid_from, valid_to,
                        references_json, payload_json, sig_scheme,
                        bbox_min_lon, bbox_min_lat, bbox_max_lon, bbox_max_lat)
                       VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                       ON CONFLICT (id) DO UPDATE SET
                         kind = EXCLUDED.kind,
                         authors = EXCLUDED.authors,
                         signatures = EXCLUDED.signatures,
                         valid_from = EXCLUDED.valid_from,
                         valid_to = EXCLUDED.valid_to,
                         references_json = EXCLUDED.references_json,
                         payload_json = EXCLUDED.payload_json,
                         sig_scheme = EXCLUDED.sig_scheme,
                         bbox_min_lon = EXCLUDED.bbox_min_lon,
                         bbox_min_lat = EXCLUDED.bbox_min_lat,
                         bbox_max_lon = EXCLUDED.bbox_max_lon,
                         bbox_max_lat = EXCLUDED.bbox_max_lat""",
                    (
                        subject.id,
                        subject.kind,
                        json.dumps(subject.authors),
                        json.dumps(subject.signatures),
                        _ensure_utc(subject.valid_from),
                        _ensure_utc(subject.valid_to) if subject.valid_to else None,
                        json.dumps(subject.references),
                        json.dumps(subject.payload, default=str),
                        subject.sig_scheme,
                        bbox[0] if bbox else None,
                        bbox[1] if bbox else None,
                        bbox[2] if bbox else None,
                        bbox[3] if bbox else None,
                    ),
                )
                cur.execute(
                    "DELETE FROM reference_index WHERE subject_id = %s",
                    (subject.id,),
                )
                for role, target in subject.references.items():
                    cur.execute(
                        """INSERT INTO reference_index (subject_id, role, target_id)
                           VALUES (%s, %s, %s)
                           ON CONFLICT DO NOTHING""",
                        (subject.id, role, target),
                    )

    # ------------------------------------------------------------------------
    # Read
    # ------------------------------------------------------------------------

    def get(self, subject_id: str) -> Optional[SignedSubject]:
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT * FROM subjects WHERE id = %s", (subject_id,))
                row = cur.fetchone()
                cols = [c.name for c in cur.description] if cur.description else []
        return _row_to_subject(cols, row) if row else None

    def find_by_kind(self, kind: str) -> list[SignedSubject]:
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT * FROM subjects WHERE kind = %s ORDER BY received_at",
                    (kind,),
                )
                rows = cur.fetchall()
                cols = [c.name for c in cur.description] if cur.description else []
        return [_row_to_subject(cols, r) for r in rows]

    def list(
        self,
        author: Optional[str] = None,
        kind: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> list[SignedSubject]:
        clauses = []
        params: list = []
        if author:
            # JSONB containment: authors @> '["hex"]'::jsonb
            clauses.append("authors @> %s::jsonb")
            params.append(json.dumps([author]))
        if kind:
            clauses.append("kind = %s")
            params.append(kind)
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        params.extend([limit, offset])
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    f"SELECT * FROM subjects{where} "
                    f"ORDER BY received_at DESC LIMIT %s OFFSET %s",
                    params,
                )
                rows = cur.fetchall()
                cols = [c.name for c in cur.description] if cur.description else []
        return [_row_to_subject(cols, r) for r in rows]

    def find_referencing(
        self, target_id: str, role: Optional[str] = None
    ) -> list[SignedSubject]:
        with self._connect() as conn:
            with conn.cursor() as cur:
                if role:
                    cur.execute(
                        """SELECT s.* FROM subjects s
                           JOIN reference_index r ON r.subject_id = s.id
                           WHERE r.target_id = %s AND r.role = %s
                           ORDER BY s.received_at""",
                        (target_id, role),
                    )
                else:
                    cur.execute(
                        """SELECT s.* FROM subjects s
                           JOIN reference_index r ON r.subject_id = s.id
                           WHERE r.target_id = %s
                           ORDER BY s.received_at""",
                        (target_id,),
                    )
                rows = cur.fetchall()
                cols = [c.name for c in cur.description] if cur.description else []
        return [_row_to_subject(cols, r) for r in rows]

    def query_active_at(
        self,
        lon: float,
        lat: float,
        at: datetime,
        kind: Optional[str] = None,
    ) -> list[SignedSubject]:
        at_utc = _ensure_utc(at)
        bbox_clause = (
            "(bbox_min_lon IS NULL OR (bbox_min_lon <= %s AND bbox_max_lon >= %s "
            "AND bbox_min_lat <= %s AND bbox_max_lat >= %s))"
        )
        time_clause = "valid_from <= %s AND (valid_to IS NULL OR valid_to > %s)"
        kind_clause = ""
        params = [lon, lon, lat, lat, at_utc, at_utc]
        if kind:
            kind_clause = " AND kind = %s"
            params.append(kind)
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    f"SELECT * FROM subjects WHERE {bbox_clause} AND {time_clause}{kind_clause}",
                    params,
                )
                rows = cur.fetchall()
                cols = [c.name for c in cur.description] if cur.description else []

        candidates = [_row_to_subject(cols, r) for r in rows]
        matched = [s for s in candidates if _point_in_subject_geometry(s, lon, lat)]
        revoked_ids = self._revoked_ids_at(matched, at_utc)
        return [s for s in matched if s.id not in revoked_ids]

    # ------------------------------------------------------------------------
    # FCM token registry (push notifikace)
    # ------------------------------------------------------------------------

    def register_fcm_token(self, author_hex: str, fcm_token: str, platform: str = "android") -> None:
        """Uloží FCM token pro autora. Idempotentní (UPSERT).

        Když driver app pošle nový token, my si ho asociujeme s jeho author_hex
        (Ed25519 public key). Při novém subjektu vystrojíme push na všechny
        tokeny autorů, kteří mají aktivní park_snapshot na zasahované poloze.
        """
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """INSERT INTO fcm_tokens (author_hex, fcm_token, platform)
                       VALUES (%s, %s, %s)
                       ON CONFLICT (author_hex, fcm_token) DO UPDATE
                         SET registered_at = NOW(),
                             platform = EXCLUDED.platform""",
                    (author_hex, fcm_token, platform),
                )

    def get_fcm_tokens_for(self, author_hex: str) -> list[str]:
        """Vrátí seznam FCM tokenů registrovaných pro autora (může mít víc zařízení)."""
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT fcm_token FROM fcm_tokens WHERE author_hex = %s",
                    (author_hex,),
                )
                return [row[0] for row in cur.fetchall()]

    def delete_fcm_token(self, fcm_token: str) -> None:
        """Smaže token (např. když FCM hlásí, že je neplatný — odinstalace appky)."""
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM fcm_tokens WHERE fcm_token = %s", (fcm_token,))

    def query_in_range(
        self,
        lon: float,
        lat: float,
        at_from: datetime,
        at_to: datetime,
        kind: Optional[str] = None,
    ) -> list[SignedSubject]:
        """Subjekty s overlapem mezi [valid_from, valid_to] a [at_from, at_to].

        Použití: public dashboard (`/upcoming`) chce *"co platí teď nebo začne
        v příštích N dnech"*. Filtruje point-in-polygon a vylučuje revokované
        (k času `at_to` — pokud byla revokace vyhlášena před koncem okna).

        Vrací chronologicky seřazené podle `valid_from` ascending.
        """
        at_from_utc = _ensure_utc(at_from)
        at_to_utc = _ensure_utc(at_to)
        bbox_clause = (
            "(bbox_min_lon IS NULL OR (bbox_min_lon <= %s AND bbox_max_lon >= %s "
            "AND bbox_min_lat <= %s AND bbox_max_lat >= %s))"
        )
        # Overlap: subjekt začíná před koncem okna A končí po začátku okna
        time_clause = "valid_from <= %s AND (valid_to IS NULL OR valid_to > %s)"
        kind_clause = ""
        params = [lon, lon, lat, lat, at_to_utc, at_from_utc]
        if kind:
            kind_clause = " AND kind = %s"
            params.append(kind)
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    f"SELECT * FROM subjects WHERE {bbox_clause} AND {time_clause}{kind_clause} "
                    f"ORDER BY valid_from ASC",
                    params,
                )
                rows = cur.fetchall()
                cols = [c.name for c in cur.description] if cur.description else []

        candidates = [_row_to_subject(cols, r) for r in rows]
        matched = [s for s in candidates if _point_in_subject_geometry(s, lon, lat)]
        revoked_ids = self._revoked_ids_at(matched, at_to_utc)
        return [s for s in matched if s.id not in revoked_ids]

    def _revoked_ids_at(
        self, subjects: list[SignedSubject], at_utc: datetime
    ) -> set[str]:
        """Vrátí ID subjektů zrušených platnou revokací k danému času.

        Bezpečnostní pravidlo: revokace platí jen pokud její `authors` se rovnají
        `authors` původního subjektu. Tj. **kdo subjekt vyhlásil, ten ho může
        zrušit** — cizí klíč nemůže neutralizovat cizí vyhlášení.

        JSONB equality přes oboustranný `@>` (set inkluze v obou směrech).
        """
        if not subjects:
            return set()
        ids = [s.id for s in subjects]
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """SELECT r.target_id FROM reference_index r
                       JOIN subjects rev ON rev.id = r.subject_id
                       JOIN subjects tgt ON tgt.id = r.target_id
                       WHERE rev.kind = 'revocation'
                         AND r.role = 'target'
                         AND r.target_id = ANY(%s)
                         AND rev.valid_from <= %s
                         AND rev.authors @> tgt.authors
                         AND tgt.authors @> rev.authors""",
                    (ids, at_utc),
                )
                return {row[0] for row in cur.fetchall()}

    # ------------------------------------------------------------------------
    # Audit
    # ------------------------------------------------------------------------

    def audit_log_for(self, subject_id: str) -> list[SignedSubject]:
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """SELECT * FROM subjects WHERE id = %s
                       UNION
                       SELECT s.* FROM subjects s
                       JOIN reference_index r ON r.subject_id = s.id
                       WHERE r.target_id = %s
                       ORDER BY received_at""",
                    (subject_id, subject_id),
                )
                rows = cur.fetchall()
                cols = [c.name for c in cur.description] if cur.description else []
        return [_row_to_subject(cols, r) for r in rows]


# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------


def _ensure_utc(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def _extract_bbox(
    subject: SignedSubject,
) -> Optional[tuple[float, float, float, float]]:
    geom = subject.payload.get("geometry")
    if not geom:
        return None
    try:
        poly = shape(geom)
        return poly.bounds  # (min_x, min_y, max_x, max_y)
    except Exception:
        return None


def _point_in_subject_geometry(
    subject: SignedSubject, lon: float, lat: float
) -> bool:
    geom = subject.payload.get("geometry")
    if not geom:
        return False
    try:
        poly = shape(geom)
        return poly.contains(Point(lon, lat))
    except Exception:
        return False


def _row_to_subject(cols: list[str], row: tuple) -> SignedSubject:
    d = dict(zip(cols, row))
    return SignedSubject(
        id=d["id"],
        kind=d["kind"],
        authors=d["authors"] if isinstance(d["authors"], list) else json.loads(d["authors"]),
        signatures=d["signatures"] if isinstance(d["signatures"], list) else json.loads(d["signatures"]),
        valid_from=d["valid_from"],
        valid_to=d["valid_to"],
        references=d["references_json"] if isinstance(d["references_json"], dict) else json.loads(d["references_json"]),
        payload=d["payload_json"] if isinstance(d["payload_json"], dict) else json.loads(d["payload_json"]),
        sig_scheme=d["sig_scheme"],
    )
