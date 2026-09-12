# Wallet & P2P Transfer Service

A small money-movement service: wallets hold an integer paise balance, and users transfer
funds peer-to-peer. Correctness under concurrency is the priority — no lost updates, no
overdrafts, no double-spends, and total money is conserved.

**Live URL:** https://wallet-transfer-k5sw.onrender.com
(free tier — first request after idle may take ~50s to wake)

## Stack
- Java 21, Spring Boot 4.1.1 (Web MVC, Data JPA, Actuator, Validation)
- PostgreSQL 16 (local) / 18 (Render managed)
- Flyway for schema migrations
- Micrometer + Prometheus for metrics; Logback JSON for structured logs
- Docker (multi-stage, non-root) + docker-compose

## Run locally (one command)
```bash
docker compose up --build
```
This starts Postgres and the app. The app waits for the DB to be healthy, Flyway creates the
schema, and it listens on http://localhost:8080.

To run just the DB and the app from the IDE:
```bash
docker compose up -d db
./gradlew bootRun   # app connects to localhost:5434
```

## Prove correctness (the concurrency gates)
```bash
./burst.sh
```
Fires 50 concurrent requests at each gate and asserts the invariants. Expected: `ALL GATES PASSED`.
- Gate 1: 50 concurrent creates for one user → exactly 1 wallet
- Gate 2: 50 concurrent identical transfers (same idempotency key) → money moves once
- Gate 3: 50 concurrent distinct transfers → no lost update, no overdraft
- Conservation: sum of balances is invariant

## API
All endpoints except `/auth/token`, `/actuator/*`, and `/metrics` require a bearer token.

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/auth/token` | exchange a userId for a bearer token (bootstrap) |
| POST | `/wallets` | get-or-create a wallet (race-free) |
| GET | `/wallets/{userId}` | current balance |
| POST | `/wallets/{userId}/credit` | fund a wallet |
| POST | `/transfers` | move money `{idempotencyKey, fromUserId, toUserId, amountPaise}` |
| GET | `/transfers/{id}` | transfer status |
| POST | `/transfers/{id}/reverse` | reverse a completed transfer (optional `Idempotency-Key` header) |
| GET | `/actuator/health` | health (open) |
| GET | `/metrics` | Prometheus metrics incl. domain counters (open) |

### Example
```bash
BASE=https://wallet-transfer-k5sw.onrender.com
TOKEN=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' \
  -d '{"userId":"alice"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

curl -X POST $BASE/wallets -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"userId":"alice"}'
curl -X POST $BASE/wallets/alice/credit -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"amountPaise":100000}'
curl -X POST $BASE/transfers -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"t1","fromUserId":"alice","toUserId":"bob","amountPaise":30000}'
```

## Observability
- **Structured JSON logs** to stdout, each line carrying a `correlationId` (from the
  `X-Correlation-Id` request header, or generated). Trace one request across all its log lines.
- **`/metrics`** exposes Prometheus text incl. domain counters: `transfers_completed`,
  `transfers_declined_insufficient_funds`, `transfers_idempotent_replay`, `transfers_conflict`,
  `transfers_reversed`.

## Config (environment variables)
| Var | Purpose | Local default |
|-----|---------|---------------|
| `DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5434/wallet` |
| `DB_USER` / `DB_PASSWORD` | DB credentials | `wallet` / `wallet` |
| `AUTH_TOKEN_SECRET` | HMAC secret for bearer tokens | `dev-secret-change-me` |

See `WRITEUP.md` for the design, trade-offs, and rejected alternatives.
