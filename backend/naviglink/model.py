"""SignedSubject — univerzální datová struktura vrstvy 0 Naviglinku.

Přebraná z prototype/21-izomorfie-subjektu.py s rozšířením pro
produkci:
  - kanonická bajt-sekvence pro podpis je deterministický JSON s ASCII
    safe encoding, aby browser (JS) a server (Python) vždy dospěly
    ke stejnému bytestreamu
  - veřejné klíče se serializují jako hex pro JSON-friendliness
  - geo polygony validovány shapely (kompatibilita s JS Leaflet GeoJSON)
"""

from __future__ import annotations

import base64
import json
from datetime import datetime, timezone
from typing import Any, Optional

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    PublicFormat,
    PrivateFormat,
    NoEncryption,
)
from pydantic import BaseModel, Field, field_validator


# ----------------------------------------------------------------------------
# Kanonický JSON pro podpisování
# ----------------------------------------------------------------------------

def canonical_json(payload: dict[str, Any]) -> bytes:
    """Deterministická bajt-sekvence pro podpisování.

    Pravidla (musí být identická v JS na klientské straně):
      - sort_keys=True
      - separators=(",", ":") — žádné zbytečné mezery
      - ensure_ascii=False — UTF-8 přímo
      - výsledek je UTF-8 bajt-sekvence
    """
    return json.dumps(
        payload,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        default=_json_default,
    ).encode("utf-8")


def _json_default(obj: Any) -> Any:
    """Serializátor pro typy, které json nezvládá natively."""
    if isinstance(obj, datetime):
        # Vždy UTC + ISO 8601 s 'Z' suffixem (kompatibilní s JS Date)
        if obj.tzinfo is None:
            obj = obj.replace(tzinfo=timezone.utc)
        return obj.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    if isinstance(obj, bytes):
        return base64.b64encode(obj).decode("ascii")
    raise TypeError(f"Type {type(obj).__name__} not serializable")


# ----------------------------------------------------------------------------
# SignedSubject
# ----------------------------------------------------------------------------

class SignedSubject(BaseModel):
    """Jeden datový typ pro všechny role Naviglinku.

    kind: "subject" | "claim" | "relation" | "schema" |
          "commitment" | "pop" | "attestation" | "revocation"
    references: pojmenované pointery na jiné subjekty
        ("about", "source", "target", "atestor", "domain", "scope", ...)
    payload: specifický obsah dané varianty (libovolný JSON dict)

    Pole `signatures` a `authors` jsou paralelní seznamy stejné délky.
    Pole `id` je content-addressed: hash(canonical_json(rest)) — tím
    se identifikátor přirozeně přenáší mezi federovanými instancemi
    bez kolizí (toto je implementace rozhodnutí 2 v TECHNOLOGICKE-VOLBY).
    """

    id: str = Field(description="Content-addressed: naviglink:<hash>")
    kind: str
    authors: list[str] = Field(default_factory=list,
                                description="Hex-encoded Ed25519 public keys")
    signatures: list[str] = Field(default_factory=list,
                                    description="Base64-encoded signatures")
    valid_from: datetime
    valid_to: Optional[datetime] = None
    references: dict[str, str] = Field(default_factory=dict)
    payload: dict[str, Any] = Field(default_factory=dict)
    sig_scheme: str = Field(default="ed25519",
                              description="Signature scheme (rozhodnutí 5 v TV)")

    # ------------------------------------------------------------------------
    # Kanonická serializace pro podpis
    # ------------------------------------------------------------------------

    def canonical_payload(self) -> bytes:
        """Bajt-sekvence, kterou podepisují autoři.

        Vynechává pole `signatures` (cyklická závislost) a `id` (content hash
        se dopočítává po podpisu). Musí být identické v browser-side JS.
        """
        d = {
            "kind": self.kind,
            "authors": sorted(self.authors),
            "valid_from": self.valid_from,
            "valid_to": self.valid_to,
            "references": self.references,
            "payload": self.payload,
            "sig_scheme": self.sig_scheme,
        }
        return canonical_json(d)

    # ------------------------------------------------------------------------
    # Verifikace
    # ------------------------------------------------------------------------

    def verify(self) -> bool:
        """Ověř všechny podpisy proti autorům.

        Vrací True jen pokud:
          - počet podpisů == počet autorů
          - všechny podpisy verifikují proti odpovídajícím veřejným klíčům
          - sig_scheme == "ed25519" (jiné zatím neimplementovány)
        """
        if self.sig_scheme != "ed25519":
            return False
        if len(self.authors) != len(self.signatures):
            return False
        if not self.authors:
            return False

        canon = self.canonical_payload()

        for author_hex, sig_b64 in zip(self.authors, self.signatures):
            try:
                pub_bytes = bytes.fromhex(author_hex)
                pub = Ed25519PublicKey.from_public_bytes(pub_bytes)
                sig = base64.b64decode(sig_b64)
                pub.verify(sig, canon)
            except (ValueError, InvalidSignature):
                return False
        return True

    # ------------------------------------------------------------------------
    # Time validity
    # ------------------------------------------------------------------------

    def is_active_at(self, t: datetime) -> bool:
        """Platí v daném čase? Bez ohledu na revokaci (revokace je
        oddělené tvrzení — řeší se v storage layer přes audit log)."""
        t_utc = t.astimezone(timezone.utc) if t.tzinfo else t.replace(tzinfo=timezone.utc)
        vf = self.valid_from.astimezone(timezone.utc) if self.valid_from.tzinfo else \
            self.valid_from.replace(tzinfo=timezone.utc)
        if t_utc < vf:
            return False
        if self.valid_to is not None:
            vt = self.valid_to.astimezone(timezone.utc) if self.valid_to.tzinfo else \
                self.valid_to.replace(tzinfo=timezone.utc)
            if t_utc >= vt:
                return False
        return True


# ----------------------------------------------------------------------------
# Klíče — pomocné funkce pro testy a CLI
# ----------------------------------------------------------------------------

def generate_keypair() -> tuple[str, Ed25519PrivateKey]:
    """Vygeneruj nový Ed25519 pár. Vrací (hex_public_key, private_key_obj).

    Veřejný klíč v hex je formát, který používáme jako *author identifier*
    v SignedSubject. Privátní klíč je objekt cryptography, který v testech
    používáme pro podepisování; v produkci by ho držel browser secure enclave.
    """
    priv = Ed25519PrivateKey.generate()
    pub_bytes = priv.public_key().public_bytes(
        encoding=Encoding.Raw, format=PublicFormat.Raw
    )
    return pub_bytes.hex(), priv


def sign_with(priv: Ed25519PrivateKey, payload_bytes: bytes) -> str:
    """Podepiš bajt-sekvenci, vrať base64."""
    sig = priv.sign(payload_bytes)
    return base64.b64encode(sig).decode("ascii")


def export_private_key_pem(priv: Ed25519PrivateKey) -> str:
    """Export privátního klíče v PEM formátu (pro persistenci v testech)."""
    pem = priv.private_bytes(
        encoding=Encoding.PEM,
        format=PrivateFormat.PKCS8,
        encryption_algorithm=NoEncryption(),
    )
    return pem.decode("ascii")
