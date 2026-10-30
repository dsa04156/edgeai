-- AI model resource measurement: a run starts one owned, bounded measurement Job of the registered image, drives real
-- inference load, reads Prometheus for the exact Pod and window, and stores the outcome. Confirmed requirements are
-- immutable revisions that cite a SUCCEEDED run. V70/V71 are unchanged; this is not a scheduler or a Runner.
CREATE TABLE edgeai.ai_model_profiling_run (
    id uuid PRIMARY KEY,
    model_id uuid NOT NULL REFERENCES edgeai.ai_model_execution(id),
    idempotency_key uuid NOT NULL UNIQUE,
    request_digest varchar(71) NOT NULL CHECK(request_digest ~ '^sha256:[a-f0-9]{64}$'),
    state varchar(16) NOT NULL CHECK(state IN ('PENDING','STARTING','WARMING_UP','MEASURING','COLLECTING','SUCCEEDED','FAILED','CANCELLED')),
    node_name varchar(253) NOT NULL,
    node_architecture varchar(32) NOT NULL,
    request jsonb NOT NULL CHECK(jsonb_typeof(request)='object'),
    job_name varchar(63) NOT NULL UNIQUE,
    job_uid uuid,
    pod_name varchar(253),
    pod_uid uuid,
    pod_ip varchar(45),
    -- The API process driving HTTP load holds a lease; another process only declares the load interrupted after it expires.
    driver_owner uuid,
    driver_lease_until timestamptz,
    message text,
    phase_started_at timestamptz NOT NULL,
    window_start timestamptz,
    window_end timestamptz,
    collect_after timestamptz,
    progress jsonb,
    failure_code varchar(32),
    failure_message text,
    result jsonb,
    cancel_requested_at timestamptz,
    finished_at timestamptz,
    cleanup_state varchar(16) NOT NULL DEFAULT 'NOT_STARTED' CHECK(cleanup_state IN ('NOT_STARTED','PENDING','DONE')),
    cleaned_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ai_model_profiling_finished CHECK((state IN ('SUCCEEDED','FAILED','CANCELLED'))=(finished_at IS NOT NULL)),
    CONSTRAINT ai_model_profiling_cleanup CHECK((cleanup_state='DONE')=(cleaned_at IS NOT NULL) AND (cleanup_state='NOT_STARTED')=(finished_at IS NULL)),
    CONSTRAINT ai_model_profiling_succeeded CHECK(state<>'SUCCEEDED' OR (result IS NOT NULL AND failure_code IS NULL
        AND window_start IS NOT NULL AND window_end IS NOT NULL AND window_end>window_start AND pod_uid IS NOT NULL AND job_uid IS NOT NULL)),
    CONSTRAINT ai_model_profiling_failed CHECK((state='FAILED')=(failure_code IS NOT NULL)),
    CONSTRAINT ai_model_profiling_cancelled CHECK(state<>'CANCELLED' OR cancel_requested_at IS NOT NULL)
);
-- A run holds its model and node until Kubernetes confirmed its Job and Pod are gone, so a new test never overlaps an old Pod.
CREATE UNIQUE INDEX ai_model_profiling_run_model_slot ON edgeai.ai_model_profiling_run(model_id) WHERE cleaned_at IS NULL;
CREATE UNIQUE INDEX ai_model_profiling_run_node_slot ON edgeai.ai_model_profiling_run(node_name) WHERE cleaned_at IS NULL;
CREATE INDEX ai_model_profiling_run_model ON edgeai.ai_model_profiling_run(model_id, created_at DESC, id);

-- A finished run's outcome is evidence: afterwards only the cleanup columns may change, and rows are never deleted.
CREATE FUNCTION edgeai.protect_ai_model_profiling_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'AI model profiling runs are kept as history' USING ERRCODE='23514';
    END IF;
    IF OLD.finished_at IS NOT NULL AND (to_jsonb(NEW)-'cleanup_state'-'cleaned_at'-'updated_at')<>(to_jsonb(OLD)-'cleanup_state'-'cleaned_at'-'updated_at') THEN
        RAISE EXCEPTION 'A finished AI model profiling run is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.cleaned_at IS NOT NULL AND NEW.cleaned_at IS DISTINCT FROM OLD.cleaned_at THEN
        RAISE EXCEPTION 'A released profiling slot cannot be reacquired' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ai_model_profiling_run_guard BEFORE UPDATE OR DELETE ON edgeai.ai_model_profiling_run
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_ai_model_profiling_run();

CREATE TABLE edgeai.ai_model_resource_profile (
    id uuid PRIMARY KEY,
    model_id uuid NOT NULL REFERENCES edgeai.ai_model_execution(id),
    revision integer NOT NULL CHECK(revision BETWEEN 1 AND 1000000),
    run_id uuid NOT NULL REFERENCES edgeai.ai_model_profiling_run(id),
    idempotency_key uuid NOT NULL UNIQUE,
    request_fingerprint varchar(71) NOT NULL CHECK(request_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    digest varchar(71) NOT NULL CHECK(digest ~ '^sha256:[a-f0-9]{64}$'),
    document jsonb NOT NULL CHECK(jsonb_typeof(document)='object'),
    created_at timestamptz NOT NULL,
    UNIQUE(model_id, revision)
);
-- Only a SUCCEEDED run of the same model can back a confirmed requirement.
CREATE FUNCTION edgeai.check_ai_model_resource_profile() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM edgeai.ai_model_profiling_run r WHERE r.id=NEW.run_id AND r.model_id=NEW.model_id AND r.state='SUCCEEDED') THEN
        RAISE EXCEPTION 'A model resource profile requires a succeeded run of the same model' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ai_model_resource_profile_run BEFORE INSERT ON edgeai.ai_model_resource_profile
    FOR EACH ROW EXECUTE FUNCTION edgeai.check_ai_model_resource_profile();
CREATE FUNCTION edgeai.protect_ai_model_resource_profile() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'AI model resource profiles are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER ai_model_resource_profile_immutable BEFORE UPDATE OR DELETE ON edgeai.ai_model_resource_profile
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_ai_model_resource_profile();
