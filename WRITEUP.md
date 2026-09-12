# Wallet & P2P Transfer — Design Write-up

**Live:** https://wallet-transfer-k5sw.onrender.com · **Repo:** https://github.com/maditya01/wallet-transfer

## Data model
Two tables (Flyway `V1__init.sql`), money as **integer paise** (`BIGINT`) — never float.

- `wallets(id, user_id UNIQUE, balance_paise, created_at)` with `CHECK (balance_paise >= 0)`.
  `UNIQUE(user_id)` is the race-free get-or-create guarantee.
- `transfers(id, idempotency_key UNIQUE, request_hash, from_wallet_id, to_wallet_id,
  amount_paise, status, reverses_transfer_id, created_at)` with `CHECK(amount_paise > 0)` and
  `CHECK(from_wallet_id <> to_wallet_id)`. `UNIQUE(idempotency_key)` is the exactly-once guarantee.
  A transfer row exists only for a completed move (failures roll back), so the ledger stays clean.

## Core mechanism (and why, over the alternatives)
Everything leans on **the database as the single point of atomic arbitration**, not application locking.

- **Get-or-create (Gate 1):** `INSERT ... ON CONFLICT (user_id) DO NOTHING` then re-select.
  One insert wins the unique index; the rest no-op. *Rejected:* read-then-insert (TOCTOU race →
  duplicate wallets), and app-level `synchronized` (breaks across multiple instances).
- **Debit (Gate 3):** one atomic conditional statement —
  `UPDATE wallets SET balance_paise = balance_paise - :amt WHERE id = :id AND balance_paise >= :amt`.
  If it updates 0 rows, funds are insufficient → the whole transfer rolls back (422). *Rejected:*
  read-balance-check-in-Java-write (lost-update race under concurrency); `SELECT ... FOR UPDATE`
  works but is heavier and needs careful lock ordering. The `CHECK (balance_paise >= 0)` is a
  second line of defence.
- **Conservation:** debit + credit + the transfer row all commit in **one `@Transactional`**;
  any failure rolls back all of it, so money is never created or destroyed.
- **Deadlock avoidance:** the two wallet rows are always touched in ascending `wallet_id` order,
  regardless of transfer direction, so opposite transfers (A→B and B→A) can't deadlock.

## Reversal (`POST /transfers/{id}/reverse`)
A reversal is **a transfer with the roles swapped** — it reuses the exact same atomic
debit+credit primitive (money flows original-recipient → original-sender) and records its own
ledger row with its own idempotency key, linked to the original via `reverses_transfer_id`.
The original is flipped `COMPLETED → REVERSED` with an atomic guard —
`UPDATE ... SET status='REVERSED' WHERE id=:id AND status='COMPLETED'` — so a transfer can be
reversed **exactly once** (a second attempt updates 0 rows → 409). I deliberately did *not*
special-case reversal logic; the ledger primitive was designed so reversal falls out of it.
**Policy note:** if the original recipient has already spent the funds, the reversal's debit
declines (422) rather than forcing the wallet negative — an intentional choice (the alternative,
allow-negative-as-debt, is what some systems do; I chose strict non-negative balances).

## Where idempotency lives
In the **same transaction** as the money movement. `INSERT INTO transfers ... ON CONFLICT
(idempotency_key) DO NOTHING` is the first step; if it inserts 0 rows the key already exists, so
we fetch the original and return it (idempotent replay) — no second money move. This placement is
deliberate: checking idempotency in a *separate* transaction before the debit would be a TOCTOU
gap that allows a double-apply under concurrency. A `request_hash` (SHA-256 of from+to+amount)
distinguishes a genuine retry (same hash → return original) from **key reuse with a different body**
(different hash → 409 Conflict), so a client can't silently overwrite one transfer's intent with another.

## Consistency vs availability
Single Postgres, strong consistency (READ COMMITTED + the atomic statements above). For a money
ledger I chose **consistency over availability** — a transfer must never double-apply or overdraw,
even if that means rejecting/erroring under a DB outage. The app is stateless, so it scales
horizontally; correctness does not depend on app-instance count because all arbitration is in the DB.

## Failure modes / operability
- Insufficient funds → 422, transfer rolls back, `transfers_declined_insufficient_funds` counter++,
  a `transfer_declined_insufficient_funds` WARN log with the request's correlation id.
- Duplicate key (retry) → original result returned, `transfers_idempotent_replay` counter++.
- Key reuse, different body → 409, `transfers_conflict` counter++.
- Reversal of an already-reversed / non-completed transfer → 409; a completed reversal
  increments `transfers_reversed`.
- DB down → app fails fast on startup / requests error; health endpoint reflects DB status.

Domain counters at `/metrics`: `transfers_completed`, `transfers_declined_insufficient_funds`,
`transfers_idempotent_replay`, `transfers_conflict`, `transfers_reversed`.
- Structured JSON logs + `correlationId` let a single request be traced end-to-end; `/metrics`
  exposes request rate/latency and the domain counters above.

## AI usage — directed vs decided
AI (an in-IDE assistant) was used as a pair: it **explained** trade-offs, drafted boilerplate, and
helped debug the deploy. I **decided** the architecture — DB-arbitrated concurrency over app locks,
idempotency-in-the-same-transaction, integer paise, deadlock ordering — and I typed, understood,
and can defend every line. Commits are incremental and reflect the real build order (see git history),
not a single AI-authored drop.

## Cost
₹0. Render free web service + free managed Postgres, single small instance. Free tier sleeps on
idle (~50s cold start) and the DB expires after 90 days — fine for this exercise; production would
use a paid always-on instance and a durable DB.

## Endpoints
`POST /auth/token`, `POST /wallets`, `GET /wallets/{userId}`, `POST /wallets/{userId}/credit`,
`POST /transfers`, `GET /transfers/{id}`, `POST /transfers/{id}/reverse`,
plus `GET /actuator/health` and `GET /metrics` (open).

## Known trade-offs / what I'd do next
- Bearer tokens are HMAC-signed and long-lived (no expiry/rotation) — a real system would use
  JWT with expiry + refresh. Auth currently gates access; enforcing `fromUserId == authenticated user`
  (can't spend someone else's wallet) is a small, clear next step.
- `/actuator/prometheus` isn't auto-wired under Boot 4's modular actuator, so metrics are exposed
  via a thin `/metrics` controller reading the Prometheus registry directly.
- At higher scale: connection pooling limits and DB write throughput are the first bottleneck;
  next step would be read replicas for balance reads and partitioning by user.
