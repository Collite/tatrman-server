-- LG-P5·S2 — prompt-log attribution columns (contracts §3, F-1). ADDITIVE over the 1.x V1 prompt_logs:
-- existing rows keep their values (new columns null / default false), and V1's TSVECTOR trigger is left
-- untouched so it fires on new-schema inserts too. The 1.x text/token columns are still populated by the
-- writer; these carry the 2.0 attribution + routing + settle facts.

ALTER TABLE prompt_logs
    ADD COLUMN IF NOT EXISTS key_id          TEXT,
    ADD COLUMN IF NOT EXISTS team_id         TEXT,
    ADD COLUMN IF NOT EXISTS cost_center     TEXT,
    ADD COLUMN IF NOT EXISTS turn_ref        TEXT,
    ADD COLUMN IF NOT EXISTS requested_model TEXT,          -- what the caller asked for
    ADD COLUMN IF NOT EXISTS served_provider TEXT,          -- actual serving provider (C-4)
    ADD COLUMN IF NOT EXISTS served_model    TEXT,
    ADD COLUMN IF NOT EXISTS fallback_from   TEXT,          -- null when the primary served
    ADD COLUMN IF NOT EXISTS stripped_params JSONB,
    ADD COLUMN IF NOT EXISTS estimated       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS cached          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS cost_usd        NUMERIC(12,6),
    ADD COLUMN IF NOT EXISTS ttfb_ms         BIGINT,
    ADD COLUMN IF NOT EXISTS trace_id        TEXT;

CREATE INDEX IF NOT EXISTS idx_prompt_logs_team ON prompt_logs(team_id);
