-- GPU/NPU image comparison scenario: one durable run at a time and immutable per-image, per-target results.
CREATE TABLE edgeai.image_comparison_run (
    id uuid PRIMARY KEY,
    idempotency_key uuid NOT NULL UNIQUE,
    requested_count integer NOT NULL CHECK (requested_count BETWEEN 1 AND 100),
    state text NOT NULL CHECK (state IN ('QUEUED','RUNNING','STOPPING','STOPPED','SUCCEEDED','FAILED','INTERRUPTED')),
    dispatched_count integer NOT NULL DEFAULT 0,
    snapshot jsonb,
    failure_code text,
    failure_message text,
    owner_instance uuid NOT NULL,
    lease_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    stop_requested_at timestamptz,
    finished_at timestamptz,
    CHECK (dispatched_count BETWEEN 0 AND requested_count),
    CHECK ((state IN ('STOPPED','SUCCEEDED','FAILED','INTERRUPTED')) = (finished_at IS NOT NULL)),
    CHECK (state NOT IN ('RUNNING','STOPPING','SUCCEEDED') OR snapshot IS NOT NULL)
);
-- At most one run is QUEUED, RUNNING or STOPPING across every API instance.
CREATE UNIQUE INDEX image_comparison_run_single_active ON edgeai.image_comparison_run ((true))
    WHERE state IN ('QUEUED','RUNNING','STOPPING');
CREATE INDEX image_comparison_run_created ON edgeai.image_comparison_run (created_at DESC, id DESC);

CREATE TABLE edgeai.image_comparison_result (
    run_id uuid NOT NULL REFERENCES edgeai.image_comparison_run(id),
    image_index integer NOT NULL CHECK (image_index BETWEEN 0 AND 99),
    target text NOT NULL CHECK (target IN ('GPU','NPU')),
    status text NOT NULL CHECK (status IN ('SUCCEEDED','FAILED')),
    top_k jsonb,
    input_sha256 text,
    inference_ms double precision,
    round_trip_ms double precision NOT NULL CHECK (round_trip_ms >= 0),
    failure_code text,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, image_index, target),
    CHECK ((status = 'SUCCEEDED') = (top_k IS NOT NULL AND input_sha256 IS NOT NULL AND inference_ms IS NOT NULL AND failure_code IS NULL))
);

CREATE FUNCTION edgeai.image_comparison_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'image_comparison_result' THEN
        RAISE EXCEPTION 'image comparison results are immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.snapshot IS NOT NULL AND NEW.snapshot IS DISTINCT FROM OLD.snapshot THEN
        RAISE EXCEPTION 'image comparison snapshot is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.finished_at IS NOT NULL THEN
        RAISE EXCEPTION 'finished image comparison run is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER image_comparison_result_immutable BEFORE UPDATE OR DELETE ON edgeai.image_comparison_result
    FOR EACH ROW EXECUTE FUNCTION edgeai.image_comparison_immutable();
CREATE TRIGGER image_comparison_run_guard BEFORE UPDATE ON edgeai.image_comparison_run
    FOR EACH ROW EXECUTE FUNCTION edgeai.image_comparison_immutable();
