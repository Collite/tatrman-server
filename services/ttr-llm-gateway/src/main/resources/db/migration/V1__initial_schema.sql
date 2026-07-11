
CREATE TABLE if not exists prompt_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255),
    model_name VARCHAR(255),
    provider VARCHAR(50),
    prompt_text TEXT,
    response_text TEXT,
    tokens_prompt INT,
    tokens_completion INT,
    duration_ms BIGINT,
    status VARCHAR(50), -- SUCCESS, ERROR
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    -- Full Text Search
    tsv TSVECTOR
);

CREATE INDEX if not exists idx_prompt_logs_tsv ON prompt_logs USING GIN(tsv);
CREATE INDEX if not exists idx_prompt_logs_created_at ON prompt_logs(created_at);

-- Trigger to update TSV
CREATE FUNCTION prompt_logs_tsvector_trigger() RETURNS trigger AS $$
BEGIN
  new.tsv :=
    setweight(to_tsvector('english', coalesce(new.prompt_text,'')), 'A') ||
    setweight(to_tsvector('english', coalesce(new.response_text,'')), 'B');
  RETURN new;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER tsvectorupdate BEFORE INSERT OR UPDATE
ON prompt_logs FOR EACH ROW EXECUTE FUNCTION prompt_logs_tsvector_trigger();

GRANT ALL PRIVILEGES ON TABLE prompt_logs TO tatrman;
GRANT USAGE, SELECT ON SEQUENCE prompt_logs_id_seq TO tatrman;

CREATE TABLE if not exists jobs (
    id VARCHAR(255) PRIMARY KEY,
    status VARCHAR(50) NOT NULL, -- QUEUED, PROCESSING, COMPLETED, ERROR
    result TEXT, -- JSON result or Error message
    request_payload JSONB, -- The original request
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX if not exists idx_jobs_status ON jobs(status);

GRANT ALL PRIVILEGES ON TABLE jobs TO tatrman;
