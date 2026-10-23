-- Hawkeye warehouse-safety scenario: one durable run at a time, one pinned sample per run, immutable once finished.
CREATE TABLE edgeai.hawkeye_safety_run (
    id uuid PRIMARY KEY,
    idempotency_key uuid NOT NULL UNIQUE,
    sample_id text NOT NULL,
    reference_label text NOT NULL,
    state text NOT NULL CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','INTERRUPTED')),
    snapshot jsonb,
    category text CHECK (category IN ('spill','forklift_violation','improper_stacking','obstacle','safe')),
    explanation text,
    raw_output text,
    done_reason text,
    input_sha256 text,
    processor text,
    size_bytes bigint,
    size_vram_bytes bigint,
    inference_ms double precision CHECK (inference_ms >= 0),
    load_ms double precision CHECK (load_ms >= 0),
    round_trip_ms double precision CHECK (round_trip_ms >= 0),
    prompt_eval_count integer,
    eval_count integer,
    failure_code text,
    failure_message text,
    owner_instance uuid NOT NULL,
    lease_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    CHECK ((state IN ('SUCCEEDED','FAILED','INTERRUPTED')) = (finished_at IS NOT NULL)),
    CHECK (state NOT IN ('RUNNING','SUCCEEDED') OR snapshot IS NOT NULL),
    -- A success always names exactly one category; a category never appears on a failed run.
    CHECK ((state = 'SUCCEEDED') = (category IS NOT NULL)),
    CHECK ((raw_output IS NULL) = (inference_ms IS NULL))
);
-- At most one run is QUEUED or RUNNING across every API instance.
CREATE UNIQUE INDEX hawkeye_safety_run_single_active ON edgeai.hawkeye_safety_run ((true)) WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX hawkeye_safety_run_created ON edgeai.hawkeye_safety_run (created_at DESC, id DESC);

CREATE FUNCTION edgeai.hawkeye_safety_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.finished_at IS NOT NULL THEN
        RAISE EXCEPTION 'finished hawkeye safety run is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.snapshot IS NOT NULL AND NEW.snapshot IS DISTINCT FROM OLD.snapshot THEN
        RAISE EXCEPTION 'hawkeye safety snapshot is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER hawkeye_safety_run_guard BEFORE UPDATE OR DELETE ON edgeai.hawkeye_safety_run
    FOR EACH ROW EXECUTE FUNCTION edgeai.hawkeye_safety_immutable();
