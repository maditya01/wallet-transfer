#!/usr/bin/env bash
#
# burst.sh — concurrency correctness proof for the wallet-transfer service.
#
# Proves three gates under concurrent load:
#   Gate 1: race-free wallet creation (50 concurrent creates -> exactly 1 wallet)
#   Gate 2: idempotency          (50 concurrent identical transfers -> money moves once)
#   Gate 3: atomic debit         (N concurrent distinct transfers -> no lost update,
#                                 no overdraft, total balance conserved)
#
# Usage:  ./burst.sh
# Requires: the app running on $BASE_URL, and the Postgres container reachable via docker.
# Exits non-zero if any invariant is violated.

set -euo pipefail

# ---- config -------------------------------------------------------------
BASE_URL="${BASE_URL:-http://localhost:8080}"
DB_CONTAINER="${DB_CONTAINER:-wallet-db}"
DB_USER="${DB_USER:-wallet}"
DB_NAME="${DB_NAME:-wallet}"
CONCURRENCY="${CONCURRENCY:-50}"        # how many parallel requests per burst
FUND_PAISE="${FUND_PAISE:-1000000}"     # seed alice with this (10,000.00)
IDEMPOTENT_AMT="${IDEMPOTENT_AMT:-5000}"
DISTINCT_AMT="${DISTINCT_AMT:-1000}"

# unique user ids per run so the script is repeatable without a DB reset
RUN_ID="$(date +%s)"
ALICE="alice_${RUN_ID}"
BOB="bob_${RUN_ID}"

pass=0; fail=0
green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }

# run a SQL scalar and trim whitespace
sql() {
  docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" | tr -d '[:space:]'
}

# Mint a bearer token for a user via the open /auth/token endpoint.
mint_token() {  # mint_token <userId>
  curl -s -X POST "$BASE_URL/auth/token" -H 'Content-Type: application/json' \
    -d "{\"userId\":\"$1\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/'
}

post() {  # post <path> <json>  — authenticated
  curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL$1" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2"
}

assert_eq() {  # assert_eq <label> <actual> <expected>
  if [[ "$2" == "$3" ]]; then green "  PASS  $1 (=$2)"; pass=$((pass+1));
  else red   "  FAIL  $1 (got $2, expected $3)"; fail=$((fail+1)); fi
}

echo "== wallet-transfer burst test =="
echo "base=$BASE_URL  concurrency=$CONCURRENCY  users=$ALICE,$BOB"
echo

# ---- auth: mint a bearer token (all API calls below are authenticated) --
TOKEN="$(mint_token "$ALICE")"
if [[ -z "$TOKEN" ]]; then red "FAILED to mint token"; exit 1; fi
echo "-- auth: minted bearer token for $ALICE --"

# ---- setup: create + fund ----------------------------------------------
echo "-- setup: create wallets, fund $ALICE with $FUND_PAISE --"
curl -s -o /dev/null -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"userId\":\"$ALICE\"}"
curl -s -o /dev/null -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"userId\":\"$BOB\"}"
curl -s -o /dev/null -X POST "$BASE_URL/wallets/$ALICE/credit" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"amountPaise\":$FUND_PAISE}"

START_TOTAL="$(sql "SELECT COALESCE(SUM(balance_paise),0) FROM wallets WHERE user_id IN ('$ALICE','$BOB');")"
echo "  starting total (alice+bob) = $START_TOTAL"
echo

# ---- Gate 1: race-free create ------------------------------------------
echo "-- Gate 1: $CONCURRENCY concurrent creates for one fresh user --"
G1_USER="g1_${RUN_ID}"
seq "$CONCURRENCY" | xargs -P"$CONCURRENCY" -I{} \
  curl -s -o /dev/null -X POST "$BASE_URL/wallets" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"userId\":\"$G1_USER\"}"
G1_COUNT="$(sql "SELECT count(*) FROM wallets WHERE user_id='$G1_USER';")"
assert_eq "exactly one wallet created" "$G1_COUNT" "1"
echo

# ---- Gate 2: idempotency under load ------------------------------------
echo "-- Gate 2: $CONCURRENCY concurrent IDENTICAL transfers (same idempotency key) --"
KEY="burst-${RUN_ID}"
ALICE_BEFORE="$(sql "SELECT balance_paise FROM wallets WHERE user_id='$ALICE';")"
seq "$CONCURRENCY" | xargs -P"$CONCURRENCY" -I{} \
  curl -s -o /dev/null -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"idempotencyKey\":\"$KEY\",\"fromUserId\":\"$ALICE\",\"toUserId\":\"$BOB\",\"amountPaise\":$IDEMPOTENT_AMT}"
ROWS="$(sql "SELECT count(*) FROM transfers WHERE idempotency_key='$KEY';")"
ALICE_AFTER="$(sql "SELECT balance_paise FROM wallets WHERE user_id='$ALICE';")"
MOVED=$((ALICE_BEFORE - ALICE_AFTER))
assert_eq "exactly one transfer row for key" "$ROWS" "1"
assert_eq "money moved exactly once"         "$MOVED" "$IDEMPOTENT_AMT"
echo

# ---- Gate 3: distinct transfers under load -----------------------------
echo "-- Gate 3: $CONCURRENCY concurrent DISTINCT transfers ($DISTINCT_AMT each) --"
ALICE_BEFORE="$(sql "SELECT balance_paise FROM wallets WHERE user_id='$ALICE';")"
BOB_BEFORE="$(sql "SELECT balance_paise FROM wallets WHERE user_id='$BOB';")"
seq "$CONCURRENCY" | xargs -P"$CONCURRENCY" -I{} \
  curl -s -o /dev/null -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"idempotencyKey\":\"diff-${RUN_ID}-{}\",\"fromUserId\":\"$ALICE\",\"toUserId\":\"$BOB\",\"amountPaise\":$DISTINCT_AMT}"
ALICE_AFTER="$(sql "SELECT balance_paise FROM wallets WHERE user_id='$ALICE';")"
BOB_AFTER="$(sql "SELECT balance_paise FROM wallets WHERE user_id='$BOB';")"
EXPECTED_MOVE=$((CONCURRENCY * DISTINCT_AMT))
assert_eq "alice debited by all N transfers (no lost update)" "$((ALICE_BEFORE - ALICE_AFTER))" "$EXPECTED_MOVE"
assert_eq "bob credited by all N transfers"                   "$((BOB_AFTER - BOB_BEFORE))"     "$EXPECTED_MOVE"

# ---- Gate 3b: overdraft protection -------------------------------------
echo
echo "-- Gate 3b: transfer larger than balance must be rejected (422) --"
HUGE=$((FUND_PAISE * 1000))
CODE="$(post "/transfers" "{\"idempotencyKey\":\"over-${RUN_ID}\",\"fromUserId\":\"$ALICE\",\"toUserId\":\"$BOB\",\"amountPaise\":$HUGE}")"
assert_eq "overdraft rejected with 422" "$CODE" "422"
NEG="$(sql "SELECT count(*) FROM wallets WHERE balance_paise < 0;")"
assert_eq "no wallet has negative balance" "$NEG" "0"
echo

# ---- conservation -------------------------------------------------------
echo "-- conservation: total balance must be unchanged --"
END_TOTAL="$(sql "SELECT COALESCE(SUM(balance_paise),0) FROM wallets WHERE user_id IN ('$ALICE','$BOB');")"
assert_eq "alice+bob total conserved" "$END_TOTAL" "$START_TOTAL"
echo

# ---- summary ------------------------------------------------------------
echo "== summary: $pass passed, $fail failed =="
if [[ "$fail" -ne 0 ]]; then red "BURST FAILED"; exit 1; fi
green "ALL GATES PASSED"
