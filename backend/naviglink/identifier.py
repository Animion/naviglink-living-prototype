"""Content-addressed identifier pro SignedSubject.

Rozhodnutí 2 v TECHNOLOGICKE-VOLBY.md: ID je hash kanonického payloadu, ne
sekvenční číslo. Tím se identifikátor přirozeně přenáší mezi instancemi
v hybridním federovaném modelu bez kolizí.

Formát: "naviglink:" + multibase("base32") + 26 znaků z BLAKE2b-128
Příklad: naviglink:vovbqgw3yc7zfpoth5wgksvuhe
"""

import base64
from hashlib import blake2b


def compute_id(canonical_payload: bytes) -> str:
    """Spočítej content-addressed ID z kanonického payloadu.

    BLAKE2b-128 (16 bajtů) → base32 (bez paddingu) → 26 znaků.
    Krátké, čitelné, kolizně bezpečné v praktickém měřítku
    (2^64 prvků kolize probability).
    """
    digest = blake2b(canonical_payload, digest_size=16).digest()
    # base32 bez paddingu, lowercase pro URL-safe formát
    encoded = base64.b32encode(digest).decode("ascii").rstrip("=").lower()
    return f"naviglink:{encoded}"


def verify_id(claimed_id: str, canonical_payload: bytes) -> bool:
    """Ověř, že claimed_id je správný content hash payloadu."""
    expected = compute_id(canonical_payload)
    return expected == claimed_id
