# Naviglink backend — deployment

## Lokální vývoj

```bash
cd prototype-live/backend
pip install -e ".[dev]"
NAVIGLINK_DB=/tmp/naviglink.db python -m uvicorn naviglink.app:app --reload --port 8000
```

Health check: `curl http://localhost:8000/healthz`
OpenAPI docs: http://localhost:8000/docs

## Testy

```bash
python -m pytest tests/ -v
```

Všech 7 unit testů pokrývá:
- Ed25519 generování a podpis
- Content-addressed ID
- Tamper detection
- SQLite store put/get
- Geo-temporal query nad polygonem
- Revokace propagation
- Audit log napříč references

## Render.com deployment

### Možnost A: GitHub auto-deploy

1. Pushni `prototype-live/` do GitHub repository
2. Na Render.com: New Web Service → Connect repo → vyber větev
3. Root Directory: `prototype-live/backend`
4. Render automaticky najde `render.yaml` a nastaví:
   - Region: Frankfurt (EU)
   - Python: 3.11
   - Build: `pip install -e .`
   - Start: `uvicorn naviglink.app:app --host 0.0.0.0 --port $PORT`
   - Health check: `/healthz`
5. Po deploy: `https://naviglink-backend.onrender.com/healthz`

### Možnost B: Render CLI (rychlejší pro iterace)

```bash
# Instalace Render CLI
brew install render   # nebo curl install

# Login
render login

# Deploy
cd prototype-live/backend
render deploy
```

### Možnost C: Docker

`Dockerfile` (vytvořit pokud bude potřeba):

```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY pyproject.toml .
COPY naviglink/ ./naviglink/
RUN pip install -e .
ENV NAVIGLINK_DB=/data/naviglink.db
VOLUME /data
EXPOSE 8000
CMD uvicorn naviglink.app:app --host 0.0.0.0 --port ${PORT:-8000}
```

## Persistence

**Free tier limitation:** Render Web Service nemá persistent disk. SQLite v `/tmp/`
se ztratí při restartu (15 min nečinnosti → spánek → wake-up s prázdnou DB).

**Pro pilot:** OK — pilot je o ověření flow, ne dlouhodobé persistenci.

**Pro produkci:** dvě cesty:
1. Render Postgres (managed addon) — změna `store.py` na PostgreSQL adapter
2. Render Disk (persistent storage) — SQLite zůstane, ale s persistent volume

## Verifikace v reálném prostředí

Po deploy ověř:

```bash
HOST="https://naviglink-backend.onrender.com"

# 1) Health
curl $HOST/healthz

# 2) OpenAPI
curl $HOST/openapi.json | python -m json.tool | head -30

# 3) Realistický flow přes test_signed_subject.py logiku
# (lokálně vygeneruj klíče, podepíš, pošli na HOST)
```

## Plán dalších kroků (Varianta C)

Po stabilizaci backendu:
1. Frontend admin web (Magistrát) — Leaflet mapa Brna, klik na ulici, vyhlásit
2. Frontend řidičský web — geolocation, vidět překryvy, reagovat
3. Reálná Ed25519 v browseru přes `@noble/ed25519`
4. Realistický dataset pro úterý 9. června 2026
5. Push notifikace (Server-Sent Events místo Web Push pro iOS kompatibilitu)
