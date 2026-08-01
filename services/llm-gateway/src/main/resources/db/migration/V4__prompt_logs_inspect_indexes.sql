-- PT arc (S2.2 T1): indexes for the prompt-log inspect surface.
--
-- `GET /v1/prompt-logs` filters by turn_ref or trace_id — the two correlation
-- keys V3 added. Neither was indexed: only created_at, team_id and the FTS
-- tsvector are. Without these, every inspect call is a sequential scan of the
-- whole prompt_logs table, which on a busy estate is the table that grows
-- fastest.
--
-- Partial indexes: both columns are nullable and are NULL for any call that
-- arrived without an X-Turn-Ref header or outside a valid span. Indexing only
-- the non-null rows keeps them small and matches the query, which never asks
-- for NULL.
CREATE INDEX IF NOT EXISTS idx_prompt_logs_turn_ref
    ON prompt_logs (turn_ref) WHERE turn_ref IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_prompt_logs_trace_id
    ON prompt_logs (trace_id) WHERE trace_id IS NOT NULL;
