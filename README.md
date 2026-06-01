# Naviglink — living prototype

*Verze 0.1, květen 2026.*

**Tento adresář je oddělený od `prototype/` notebooků.** Notebook prototypy (#01–#23) ověřily *designový model* (vrstva 0 jako SignedSubject, vrstvená Sybil obrana, geometric agregace, izomorfie subjektů). Living prototype ověřuje *realný systém v reálném prostředí* — produkční-grade HTTP server, perzistentní úložiště, reálné Ed25519 podpisy v browseru, deployment na cloudový server.

## Struktura

```
prototype-live/
├── backend/              # FastAPI server (Python 3.11+)
│   ├── naviglink/        # Naviglink package
│   ├── tests/            # pytest testy
│   └── pyproject.toml    # dependency management
├── frontend-admin/       # Magistrátní web (vanilla HTML+CSS+JS)
├── frontend-driver/      # Řidičský mobil web (vanilla HTML+CSS+JS)
├── data/                 # Reálná data (harmonogram blokového čištění)
└── README.md             # tento dokument
```

## Plán: Varianta A → Varianta C

### Varianta A (warm-up, 2–3 dny) — **běží teď**

Backend skeleton s minimálním API:

- `POST /subjects` — vytvořit subjekt s podpisem
- `POST /claims` — přidat tvrzení
- `GET /query?lon=X&lat=Y&time=T` — co platí tady a teď
- `GET /audit/{subject_id}` — audit log subjektu
- `GET /healthz` — health check
- SQLite úložiště (později PostgreSQL)
- Reálné Ed25519 podpisy přes `cryptography` lib
- Curl/HTTPie tester
- Deploy na Render.com

**Cíl:** ověřit, že vrstva 0 z #21 (SignedSubject) funguje v reálném HTTP prostředí. Mít public URL, na který lze poslat curl a dostat odpověď.

### Varianta C (end-to-end, 1–2 týdny) — **po dokončení A**

Plný flow blokového čištění:

- Admin web (Magistrát): mapa Brna (Leaflet + OpenStreetMap), klik na ulici, nakreslit polygon, vyhlásit blokové čištění s časovou platností
- Mobil web (řidič): geolocation, vidět překryvy s aktivními subjekty, reagovat na upozornění
- Reálná Ed25519 v browseru přes `@noble/ed25519`
- Reálná data: realistický harmonogram pro úterý 9. června 2026, ulice Veveří, Lidická, Joštova, Husova

**Cíl:** demonstrovat *fungující systém pro pomoc lidem* — řidič ušetří 3–5 tis. Kč za odtah, Magistrát má méně logistické zátěže.

## Stack

**Backend:** Python 3.11 + FastAPI + Pydantic v2 + cryptography + SQLite (later Postgres)
**Frontend:** Vanilla HTML + pokročilé CSS + vanilla JS + `@noble/ed25519` CDN + Leaflet
**Deployment:** Render.com (backend), Render Static Sites nebo Vercel (frontend)
**Identita:** Reálné Ed25519 podpisy na obou stranách (server i klient)

## Klíčové principy living prototypu

1. **Žádné zkratky pro produkci.** Reálné podpisy, ne mock. Reálné HTTPS, ne in-memory request. Reálné cloud hosting, ne localhost.
2. **Žádné zbytečné komplikace.** Vanilla HTML+CSS místo React, SQLite místo Postgres do scale-up, žádné microservices.
3. **Continuita s notebook prototypy.** SignedSubject model přebrán z `prototype/21-izomorfie-subjektu.py`. Stejná struktura, stejná sémantika.
4. **Skutečný přínos pro lidi.** Cíl není demo pro investory, ale fungující nástroj. Pokud řidiči neušetří pokutu, prototyp selhal bez ohledu na technickou eleganci.

## Vztah ke zbytku projektu

- **`prototype/`** — notebook experimenty #01–#23 (modelová validace)
- **`prototype-live/`** — tento adresář (real-world testing)
- **`PRINCIPLES.md`** — designové principy, kterým musí kód odpovídat
- **`whitepaper/`** — komunikační dokument popisující vizi
- **`STAV.md`** — exekutivní souhrn
- **`TECHNOLOGICKE-VOLBY.md`** — strategická rozhodnutí, která living prototype prakticky testuje

---

*Pokud něco rozbije se uprostřed iterace, nezalepujeme to mockem — najdeme příčinu. Cíl je porozumění, ne demo.*
