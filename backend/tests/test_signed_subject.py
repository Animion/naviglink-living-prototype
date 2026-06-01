"""Test end-to-end flow: vytvoření, podpis, content ID, verifikace, store, query."""

from datetime import datetime, timedelta, timezone

import pytest

from naviglink.model import SignedSubject, generate_keypair, sign_with
from naviglink.identifier import compute_id
from naviglink.store import Store


# Polygon ulice Veveří v Brně (zjednodušený box pro test)
VEVERI_POLYGON = {
    "type": "Polygon",
    "coordinates": [[
        [16.5900, 49.2000],
        [16.5950, 49.2000],
        [16.5950, 49.2005],
        [16.5900, 49.2005],
        [16.5900, 49.2000],
    ]],
}


def _make_blokove_cisteni(priv_key, pub_hex) -> SignedSubject:
    """Vytvoř podepsaný subjekt 'blokové čištění Veveří 2026-06-09 6:00-10:00'."""
    s = SignedSubject(
        id="placeholder",  # vyplníme po výpočtu hashe
        kind="subject",
        authors=[pub_hex],
        signatures=[],
        valid_from=datetime(2026, 6, 9, 6, 0, tzinfo=timezone.utc),
        valid_to=datetime(2026, 6, 9, 10, 0, tzinfo=timezone.utc),
        references={"domain": "traffic_cz"},
        payload={
            "typ": "blokove_cisteni",
            "ulice": "Veveří",
            "geometry": VEVERI_POLYGON,
        },
    )
    canon = s.canonical_payload()
    sig = sign_with(priv_key, canon)
    real_id = compute_id(canon)
    return s.model_copy(update={"id": real_id, "signatures": [sig]})


def test_keypair_and_sign_verify():
    """Vygeneruj klíč, podepiš subjekt, ověř."""
    pub_hex, priv = generate_keypair()
    assert len(pub_hex) == 64  # 32 bajtů hex = 64 znaků

    subj = _make_blokove_cisteni(priv, pub_hex)
    assert subj.verify() is True


def test_content_addressed_id():
    """ID je hash payloadu — stejný payload = stejné ID."""
    pub_hex, priv = generate_keypair()
    subj1 = _make_blokove_cisteni(priv, pub_hex)
    subj2 = _make_blokove_cisteni(priv, pub_hex)
    # Tytéž autoři, payload, časy → stejné kanonické bajt-sekvence
    # ALE jiné podpisy (deterministicky stejné u Ed25519 ale to nás nezajímá)
    assert subj1.id == subj2.id
    assert subj1.canonical_payload() == subj2.canonical_payload()


def test_tamper_detection():
    """Pokud někdo změní payload, podpis selže."""
    pub_hex, priv = generate_keypair()
    subj = _make_blokove_cisteni(priv, pub_hex)
    tampered = subj.model_copy(update={
        "payload": {**subj.payload, "ulice": "Praha"}
    })
    # Stejné signatures, ale payload jiný → verify musí selhat
    assert tampered.verify() is False


def test_store_put_and_get(tmp_path):
    """Ulož a vyzvedni."""
    store = Store(tmp_path / "test.db")
    pub_hex, priv = generate_keypair()
    subj = _make_blokove_cisteni(priv, pub_hex)
    store.put(subj)
    retrieved = store.get(subj.id)
    assert retrieved is not None
    assert retrieved.id == subj.id
    assert retrieved.verify() is True


def test_query_active_at(tmp_path):
    """Geo-temporal dotaz — bod uvnitř polygonu v platném čase."""
    store = Store(tmp_path / "test.db")
    pub_hex, priv = generate_keypair()
    subj = _make_blokove_cisteni(priv, pub_hex)
    store.put(subj)

    # Bod uvnitř Veveří polygonu, v platném čase
    matches = store.query_active_at(
        lon=16.5925,
        lat=49.2002,
        at=datetime(2026, 6, 9, 7, 0, tzinfo=timezone.utc),
    )
    assert len(matches) == 1
    assert matches[0].id == subj.id

    # Bod uvnitř polygonu ale mimo časový rozsah
    matches = store.query_active_at(
        lon=16.5925,
        lat=49.2002,
        at=datetime(2026, 6, 9, 11, 0, tzinfo=timezone.utc),
    )
    assert len(matches) == 0

    # Bod mimo polygon v platném čase
    matches = store.query_active_at(
        lon=16.6500,
        lat=49.2100,
        at=datetime(2026, 6, 9, 7, 0, tzinfo=timezone.utc),
    )
    assert len(matches) == 0


def test_revocation_filters_out(tmp_path):
    """Revokace v store schová subjekt z query."""
    store = Store(tmp_path / "test.db")
    pub_hex, priv = generate_keypair()
    subj = _make_blokove_cisteni(priv, pub_hex)
    store.put(subj)

    # Před revokací — subjekt je viditelný
    matches = store.query_active_at(
        lon=16.5925, lat=49.2002,
        at=datetime(2026, 6, 9, 7, 0, tzinfo=timezone.utc),
    )
    assert len(matches) == 1

    # Vystav revokaci platnou od 2026-06-09 6:30
    rev = SignedSubject(
        id="placeholder",
        kind="revocation",
        authors=[pub_hex],
        signatures=[],
        valid_from=datetime(2026, 6, 9, 6, 30, tzinfo=timezone.utc),
        references={"target": subj.id},
        payload={"duvod": "úklid přesunut"},
    )
    rev_canon = rev.canonical_payload()
    rev_sig = sign_with(priv, rev_canon)
    rev_id = compute_id(rev_canon)
    rev = rev.model_copy(update={"id": rev_id, "signatures": [rev_sig]})
    store.put(rev)

    # Po revokaci — subjekt je odfiltrován (query čas 7:00 > revokace 6:30)
    matches = store.query_active_at(
        lon=16.5925, lat=49.2002,
        at=datetime(2026, 6, 9, 7, 0, tzinfo=timezone.utc),
    )
    assert len(matches) == 0

    # Před revokací — stále viditelný (query čas 6:15 < revokace 6:30)
    matches = store.query_active_at(
        lon=16.5925, lat=49.2002,
        at=datetime(2026, 6, 9, 6, 15, tzinfo=timezone.utc),
    )
    assert len(matches) == 1


def test_list_by_author(tmp_path):
    """store.list(author=...) vrátí jen subjekty podepsané daným autorem."""
    store = Store(tmp_path / "test.db")

    # Dva autoři, každý vyhlásí jeden subjekt
    pub_a, priv_a = generate_keypair()
    pub_b, priv_b = generate_keypair()

    s_a = _make_blokove_cisteni(priv_a, pub_a)
    s_b = _make_blokove_cisteni(priv_b, pub_b)
    store.put(s_a)
    store.put(s_b)

    a_only = store.list(author=pub_a)
    assert len(a_only) == 1
    assert a_only[0].id == s_a.id

    b_only = store.list(author=pub_b)
    assert len(b_only) == 1
    assert b_only[0].id == s_b.id

    all_subjects = store.list()
    assert len(all_subjects) == 2


def test_list_by_kind(tmp_path):
    """store.list(kind=...) vrátí jen subjekty daného typu."""
    store = Store(tmp_path / "test.db")
    pub_hex, priv = generate_keypair()

    subj = _make_blokove_cisteni(priv, pub_hex)
    store.put(subj)

    # Plus claim k tomuto subjektu
    claim = SignedSubject(
        id="placeholder", kind="claim",
        authors=[pub_hex], signatures=[],
        valid_from=datetime(2026, 6, 9, 8, 0, tzinfo=timezone.utc),
        references={"about": subj.id},
        payload={"state": "v_probehu"},
    )
    canon = claim.canonical_payload()
    sig = sign_with(priv, canon)
    claim = claim.model_copy(update={"id": compute_id(canon), "signatures": [sig]})
    store.put(claim)

    subjects_only = store.list(kind="subject")
    assert len(subjects_only) == 1
    assert subjects_only[0].kind == "subject"

    claims_only = store.list(kind="claim")
    assert len(claims_only) == 1
    assert claims_only[0].kind == "claim"


def test_list_pagination(tmp_path):
    """limit + offset funguje."""
    store = Store(tmp_path / "test.db")
    pub_hex, priv = generate_keypair()

    # Vyhlásit 5 subjektů (s drobně odlišnými časy aby měly různá ID)
    for hour in range(5, 10):
        subj = SignedSubject(
            id="placeholder", kind="subject",
            authors=[pub_hex], signatures=[],
            valid_from=datetime(2026, 6, 9, hour, 0, tzinfo=timezone.utc),
            valid_to=datetime(2026, 6, 9, hour + 1, 0, tzinfo=timezone.utc),
            references={"domain": "traffic_cz"},
            payload={"typ": "blokove_cisteni", "ulice": f"ulice-{hour}",
                     "geometry": VEVERI_POLYGON},
        )
        canon = subj.canonical_payload()
        sig = sign_with(priv, canon)
        subj = subj.model_copy(update={"id": compute_id(canon), "signatures": [sig]})
        store.put(subj)

    first_two = store.list(limit=2)
    next_two = store.list(limit=2, offset=2)
    assert len(first_two) == 2
    assert len(next_two) == 2
    assert first_two[0].id != next_two[0].id


def test_audit_log_finds_referencing(tmp_path):
    """Audit log najde subjekt sám i to, co na něj odkazuje."""
    store = Store(tmp_path / "test.db")
    pub_hex, priv = generate_keypair()
    subj = _make_blokove_cisteni(priv, pub_hex)
    store.put(subj)

    # Vystav claim o subjektu
    claim = SignedSubject(
        id="placeholder",
        kind="claim",
        authors=[pub_hex],
        signatures=[],
        valid_from=datetime(2026, 6, 9, 8, 0, tzinfo=timezone.utc),
        references={"about": subj.id},
        payload={"state": "v_probehu", "msg": "úklid pokračuje"},
    )
    claim_canon = claim.canonical_payload()
    claim_sig = sign_with(priv, claim_canon)
    claim_id = compute_id(claim_canon)
    claim = claim.model_copy(update={"id": claim_id, "signatures": [claim_sig]})
    store.put(claim)

    log = store.audit_log_for(subj.id)
    # Měly by být dva: sám subjekt + claim o něm
    ids = {s.id for s in log}
    assert subj.id in ids
    assert claim.id in ids
    assert len(ids) == 2
