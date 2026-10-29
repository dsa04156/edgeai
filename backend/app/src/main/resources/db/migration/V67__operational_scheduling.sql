-- Immutable caller-supplied measurements of the artifact-store BATCH path and each scheduled Run's original decision.
-- The server-selected NODE map is stored in workflow_run.task_executions, so the V29 triggers still pin Task/Attempt placement.
CREATE TABLE edgeai.scheduling_profile (
    id uuid PRIMARY KEY,
    workflow_version_id uuid NOT NULL REFERENCES edgeai.workflow_version(id),
    idempotency_key uuid NOT NULL UNIQUE,
    digest varchar(71) NOT NULL,
    measured_at timestamptz NOT NULL,
    valid_until timestamptz NOT NULL CHECK(valid_until>measured_at),
    document jsonb NOT NULL CHECK(jsonb_typeof(document)='object'),
    created_at timestamptz NOT NULL
);
ALTER TABLE edgeai.workflow_run ADD COLUMN scheduling_profile_id uuid REFERENCES edgeai.scheduling_profile(id);
CREATE TABLE edgeai.workflow_run_scheduling (
    run_id uuid PRIMARY KEY REFERENCES edgeai.workflow_run(id),
    profile_id uuid NOT NULL REFERENCES edgeai.scheduling_profile(id),
    profile_digest varchar(71) NOT NULL,
    method varchar(32) NOT NULL,
    decision jsonb NOT NULL CHECK(jsonb_typeof(decision)='object'),
    created_at timestamptz NOT NULL
);

CREATE FUNCTION edgeai.protect_scheduling_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Scheduling profiles and decisions are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER scheduling_profile_immutable BEFORE UPDATE OR DELETE ON edgeai.scheduling_profile
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_scheduling_record();
CREATE TRIGGER workflow_run_scheduling_immutable BEFORE UPDATE OR DELETE ON edgeai.workflow_run_scheduling
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_scheduling_record();
CREATE TRIGGER workflow_run_scheduling_profile_immutable BEFORE UPDATE OF scheduling_profile_id ON edgeai.workflow_run
    FOR EACH ROW WHEN (NEW.scheduling_profile_id IS DISTINCT FROM OLD.scheduling_profile_id)
    EXECUTE FUNCTION edgeai.protect_scheduling_record();
