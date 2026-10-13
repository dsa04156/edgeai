CREATE TABLE edgeai.continuous_execution (
    vd_id uuid PRIMARY KEY REFERENCES edgeai.virtual_device(id),
    generation uuid NOT NULL, running boolean NOT NULL,
    registry_revision bigint NOT NULL,
    state varchar(32) NOT NULL CHECK(state IN ('STOPPED','STARTING','RUNNING','WAITING_INPUT','STOPPING','ERROR')),
    reason varchar(100) NOT NULL, updated_at timestamptz NOT NULL
);
CREATE TABLE edgeai.processing_engine_session (
    engine_id varchar(63) PRIMARY KEY, pod_uid uuid NOT NULL, session_id uuid NOT NULL,
    sequence bigint NOT NULL CHECK(sequence BETWEEN 1 AND 9007199254740991),
    poll_digest varchar(71) NOT NULL, lease_expires_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE TABLE edgeai.continuous_operation (
    id uuid PRIMARY KEY, vd_id uuid NOT NULL REFERENCES edgeai.virtual_device(id),
    request_key uuid NOT NULL, requested_revision bigint NOT NULL,
    action varchar(16) NOT NULL CHECK(action IN ('START','REPLACE','STOP')),
    generation uuid NOT NULL, state varchar(16) NOT NULL CHECK(state IN ('PENDING','SUCCEEDED','SUPERSEDED','FAILED')),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, finished_at timestamptz,
    UNIQUE(vd_id,request_key)
);
CREATE TABLE edgeai.continuous_latest (
    vd_id uuid NOT NULL REFERENCES edgeai.virtual_device(id), output_id varchar(63) NOT NULL,
    generation uuid NOT NULL, evaluation_id varchar(100) NOT NULL, sequence bigint NOT NULL,
    evaluation_at timestamptz NOT NULL, result jsonb NOT NULL,
    PRIMARY KEY(vd_id,output_id)
);
CREATE TABLE edgeai.continuous_gap (
    id bigserial PRIMARY KEY, vd_id uuid NOT NULL REFERENCES edgeai.virtual_device(id),
    generation uuid NOT NULL, reason varchar(100) NOT NULL,
    started_at timestamptz NOT NULL, ended_at timestamptz,
    CHECK(ended_at IS NULL OR ended_at>=started_at)
);
CREATE UNIQUE INDEX continuous_one_gap ON edgeai.continuous_gap(vd_id,reason) WHERE ended_at IS NULL;
CREATE INDEX continuous_gap_period ON edgeai.continuous_gap(vd_id,started_at DESC);

-- An explicit registry edit fences pending results within the same transaction.
CREATE FUNCTION edgeai.fence_continuous_registry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE edgeai.continuous_operation SET state='SUPERSEDED',updated_at=NEW.updated_at,finished_at=NEW.updated_at
        WHERE vd_id=NEW.id AND state='PENDING';
    UPDATE edgeai.continuous_execution SET generation=gen_random_uuid(),registry_revision=NEW.revision,
        running=(running AND NEW.state='REGISTERED' AND NEW.service_profile_version_id IS NULL),
        state=CASE WHEN NEW.state='RELEASED' OR NEW.service_profile_version_id IS NOT NULL OR NOT running THEN 'STOPPED' ELSE 'STARTING' END,
        reason='REGISTRY_CHANGED',updated_at=NEW.updated_at WHERE vd_id=NEW.id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER fence_continuous_registry AFTER UPDATE OF revision ON edgeai.virtual_device
    FOR EACH ROW EXECUTE FUNCTION edgeai.fence_continuous_registry();
