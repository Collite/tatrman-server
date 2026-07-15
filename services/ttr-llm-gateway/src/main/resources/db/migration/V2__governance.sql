-- LG-P4·S1 — governance domain (contracts §3). Additive over the 1.x V1 schema; prompt_logs/jobs untouched.
-- Teams are config-sourced (governance.yaml) and upserted at startup for FK integrity; virtual keys and
-- monthly budget counters live here. Plaintext keys are NEVER stored — only their SHA-256 hex (D-1).

CREATE TABLE IF NOT EXISTS teams (
    id                 TEXT PRIMARY KEY,          -- from governance.yaml (config is source of truth)
    cost_center_prefix TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS virtual_keys (
    id           TEXT PRIMARY KEY,                -- vk_<ulid>
    team_id      TEXT        NOT NULL REFERENCES teams(id),
    name         TEXT        NOT NULL,
    key_hash     TEXT        NOT NULL UNIQUE,      -- SHA-256 hex; plaintext never stored (D-1)
    seeded       BOOLEAN     NOT NULL DEFAULT FALSE, -- G-3 imports
    budget_usd   NUMERIC(12,6),                    -- per-key override, nullable (min-wins)
    rpm_limit    INT,                              -- per-key override, nullable
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_virtual_keys_team ON virtual_keys(team_id);

CREATE TABLE IF NOT EXISTS budget_usage (        -- calendar-monthly counters (D-3)
    team_id         TEXT          NOT NULL REFERENCES teams(id),
    month           DATE          NOT NULL,        -- first of month, UTC
    used_usd        NUMERIC(14,6) NOT NULL DEFAULT 0,
    used_tokens_in  BIGINT        NOT NULL DEFAULT 0,
    used_tokens_out BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (team_id, month)
);

GRANT ALL PRIVILEGES ON TABLE teams, virtual_keys, budget_usage TO "${appRole}";
