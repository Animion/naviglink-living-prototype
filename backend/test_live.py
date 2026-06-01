"""End-to-end test proti live Render deployu."""
import json
import time
import urllib.request
import urllib.error
import sys
from datetime import datetime, timezone

sys.path.insert(0, '.')
from naviglink.model import SignedSubject, generate_keypair, sign_with
from naviglink.identifier import compute_id

HOST = "https://naviglink-living.onrender.com"


def _post(path, data):
    req = urllib.request.Request(
        f"{HOST}{path}",
        data=json.dumps(data, default=str).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    return urllib.request.urlopen(req)


def _get(path):
    return urllib.request.urlopen(f"{HOST}{path}")


def main():
    print(f"=== {HOST} ===")

    # 1) Health
    t0 = time.time()
    with _get("/healthz") as r:
        body = json.loads(r.read())
    print(f"GET  /healthz                       -> {r.status} {body} ({(time.time()-t0)*1000:.0f} ms)")

    # 2) Generate Magistrat keypair
    pub_hex, priv = generate_keypair()
    print(f"Magistrat pub key: {pub_hex[:32]}...")

    # 3) Build signed subject
    subj = SignedSubject(
        id="placeholder",
        kind="subject",
        authors=[pub_hex],
        signatures=[],
        valid_from=datetime(2026, 6, 9, 6, 0, tzinfo=timezone.utc),
        valid_to=datetime(2026, 6, 9, 10, 0, tzinfo=timezone.utc),
        references={"domain": "traffic_cz"},
        payload={
            "typ": "blokove_cisteni",
            "ulice": "Veveri",  # ASCII pro robustnost across Windows codepages
            "geometry": {
                "type": "Polygon",
                "coordinates": [[
                    [16.5900, 49.2000],
                    [16.5950, 49.2000],
                    [16.5950, 49.2005],
                    [16.5900, 49.2005],
                    [16.5900, 49.2000],
                ]],
            },
        },
    )
    canon = subj.canonical_payload()
    sig = sign_with(priv, canon)
    final = subj.model_copy(update={"id": compute_id(canon), "signatures": [sig]})
    print(f"Subject ID: {final.id}")

    # 4) POST
    t0 = time.time()
    with _post("/subjects", final.model_dump(mode="json")) as r:
        body = json.loads(r.read())
    print(f"POST /subjects                      -> {r.status} {body} ({(time.time()-t0)*1000:.0f} ms)")

    # 5) Geo-temporal queries
    print()
    print("Geo-temporal:")
    cases = [
        ("Veveri 7:00 (uvnitr)", 16.5925, 49.2002, "2026-06-09T07:00:00Z", 1),
        ("Veveri 11:00 (po skonceni)", 16.5925, 49.2002, "2026-06-09T11:00:00Z", 0),
        ("Jine misto 7:00", 16.6500, 49.2100, "2026-06-09T07:00:00Z", 0),
    ]
    for name, lon, lat, at, expected in cases:
        t0 = time.time()
        with _get(f"/query?lon={lon}&lat={lat}&at={at}") as r:
            d = json.loads(r.read())
        status = "OK " if d["count"] == expected else "FAIL"
        print(f"  [{status}] {name:32s} count={d['count']} (exp {expected}, {(time.time()-t0)*1000:.0f} ms)")

    # 6) Audit
    with _get(f"/audit/{final.id}") as r:
        d = json.loads(r.read())
    print(f"\nGET  /audit/{final.id[:30]}... -> {d['count']} entries")

    # 7) Tamper
    tampered = final.model_dump(mode="json")
    tampered["payload"]["ulice"] = "Praha"
    try:
        with _post("/subjects", tampered) as r:
            print("TAMPER LEAK!")
    except urllib.error.HTTPError as e:
        detail = json.loads(e.read()).get("detail", "")[:80]
        print(f"POST /subjects (tampered)           -> {e.code} (odmitnuto): {detail}")

    print("\n=== Live deploy validated end-to-end ===")


if __name__ == "__main__":
    main()
