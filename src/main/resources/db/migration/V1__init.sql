-- wallets: one per user. UNIQUE(user_id) is the Gate 1 guarantee.
CREATE TABLE wallets (
                         id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                         user_id       VARCHAR(64)  NOT NULL UNIQUE,      -- DB refuses duplicate wallets per user
                         balance_paise BIGINT       NOT NULL DEFAULT 0,   -- money in paise (integer) — NEVER float
                         created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                         CONSTRAINT balance_non_negative CHECK (balance_paise >= 0)  -- balance can never go below zero
);

-- transfers: an immutable ledger of money movement.
CREATE TABLE transfers (
                           id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                           idempotency_key      VARCHAR(128) NOT NULL UNIQUE,   -- Gate 2: same key can't process twice
                           request_hash         VARCHAR(64)  NOT NULL,          -- detect same key + different body -> 409
                           from_wallet_id       BIGINT       NOT NULL REFERENCES wallets(id),
                           to_wallet_id         BIGINT       NOT NULL REFERENCES wallets(id),
                           amount_paise         BIGINT       NOT NULL,
                           status               VARCHAR(16)  NOT NULL,          -- e.g. COMPLETED, FAILED
                           reverses_transfer_id BIGINT       REFERENCES transfers(id),  -- nullable; set when this is a reversal (R3)
                           created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
                           CONSTRAINT amount_positive CHECK (amount_paise > 0),
                           CONSTRAINT no_self_transfer CHECK (from_wallet_id <> to_wallet_id)
);

CREATE INDEX idx_transfers_from_wallet ON transfers(from_wallet_id);
CREATE INDEX idx_transfers_to_wallet   ON transfers(to_wallet_id);
