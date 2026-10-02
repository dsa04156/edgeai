-- A Remote runtime never impersonates a Kubernetes Job, Pod or Node.
ALTER TABLE edgeai.runtime_instance
    ADD COLUMN remote_allocation_id uuid,
    ALTER COLUMN job_name DROP NOT NULL,
    ADD CONSTRAINT runtime_remote_identity UNIQUE(id,remote_allocation_id);
ALTER TABLE edgeai.runtime_instance DROP CONSTRAINT runtime_instance_check1;
ALTER TABLE edgeai.runtime_instance ADD CONSTRAINT runtime_producer_kind CHECK(
    (remote_allocation_id IS NULL AND job_name IS NOT NULL AND (observed_state<>'RUNNING' OR producer_pod_uid IS NOT NULL)) OR
    (remote_allocation_id IS NOT NULL AND job_name IS NULL AND job_uid IS NULL AND producer_pod_uid IS NULL AND node_uid IS NULL AND node_name IS NULL AND expires_at IS NOT NULL));
ALTER TABLE edgeai.runtime_instance
    ADD COLUMN runtime_kind varchar(10) GENERATED ALWAYS AS (CASE WHEN remote_allocation_id IS NULL THEN 'KUBERNETES' ELSE 'REMOTE' END) STORED,
    ADD CONSTRAINT runtime_kind_identity UNIQUE(id,runtime_kind),
    ADD CONSTRAINT runtime_pod_identity UNIQUE(id,producer_pod_uid);

CREATE TABLE edgeai.remote_allocation (
    id uuid PRIMARY KEY, runtime_id uuid NOT NULL UNIQUE,
    provider_key varchar(63) NOT NULL CHECK(provider_key ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    configuration_digest varchar(71) NOT NULL CHECK(configuration_digest ~ '^sha256:[a-f0-9]{64}$'),
    source_mode varchar(9) NOT NULL CHECK(source_mode IN ('SYNTHETIC','EXTERNAL')),
    work jsonb NOT NULL CHECK(jsonb_typeof(work)='object'),
    request_digest varchar(71) NOT NULL CHECK(request_digest ~ '^sha256:[a-f0-9]{64}$'),
    provider_revision bigint NOT NULL DEFAULT 0 CHECK(provider_revision BETWEEN 0 AND 9007199254740991),
    provider_state varchar(16) NOT NULL DEFAULT 'UNKNOWN' CHECK(provider_state IN ('UNKNOWN','ALLOCATED','RUNNING','SUCCEEDED','FAILED','CANCELLING','CANCELLED')),
    observation jsonb CHECK(observation IS NULL OR jsonb_typeof(observation)='object'),
    observed_at timestamptz, created_at timestamptz NOT NULL,
    CHECK((provider_revision=0 AND provider_state='UNKNOWN' AND observation IS NULL AND observed_at IS NULL) OR
          (provider_revision>0 AND provider_state<>'UNKNOWN' AND observation IS NOT NULL AND observed_at IS NOT NULL)),
    FOREIGN KEY(runtime_id,id) REFERENCES edgeai.runtime_instance(id,remote_allocation_id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE edgeai.runtime_instance ADD CONSTRAINT runtime_remote_allocation
    FOREIGN KEY(remote_allocation_id) REFERENCES edgeai.remote_allocation(id) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION edgeai.protect_remote_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND
       (to_jsonb(OLD)-ARRAY['provider_revision','provider_state','observation','observed_at'])=
       (to_jsonb(NEW)-ARRAY['provider_revision','provider_state','observation','observed_at']) AND
       NEW.provider_revision>=OLD.provider_revision THEN
        IF NEW.provider_revision=OLD.provider_revision AND
           (NEW.provider_state IS DISTINCT FROM OLD.provider_state OR NEW.observation IS DISTINCT FROM OLD.observation) THEN
            RAISE EXCEPTION 'Remote revision cannot fork' USING ERRCODE='23514';
        END IF;
        IF OLD.provider_state IN ('SUCCEEDED','FAILED','CANCELLED') AND NEW.observation IS DISTINCT FROM OLD.observation THEN
            RAISE EXCEPTION 'Remote terminal observation is immutable' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Remote allocation identity and work are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER remote_allocation_immutable BEFORE UPDATE OR DELETE ON edgeai.remote_allocation
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_remote_allocation();
CREATE TRIGGER remote_allocation_no_truncate BEFORE TRUNCATE ON edgeai.remote_allocation
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_remote_allocation();

ALTER TABLE edgeai.task_result
    ADD COLUMN remote_allocation_id uuid REFERENCES edgeai.remote_allocation(id),
    ADD COLUMN producer_kind varchar(10) GENERATED ALWAYS AS (CASE WHEN producer_pod_uid IS NULL THEN 'REMOTE' ELSE 'KUBERNETES' END) STORED,
    ALTER COLUMN producer_pod_uid DROP NOT NULL,
    ADD CONSTRAINT result_producer_kind CHECK((producer_pod_uid IS NULL)<>(remote_allocation_id IS NULL)),
    ADD CONSTRAINT result_remote_runtime FOREIGN KEY(runtime_id,remote_allocation_id)
        REFERENCES edgeai.runtime_instance(id,remote_allocation_id),
    ADD CONSTRAINT result_runtime_kind FOREIGN KEY(runtime_id,producer_kind) REFERENCES edgeai.runtime_instance(id,runtime_kind),
    ADD CONSTRAINT result_pod_runtime FOREIGN KEY(runtime_id,producer_pod_uid) REFERENCES edgeai.runtime_instance(id,producer_pod_uid);

-- Public REMOTE request handling and provider selection are connected in the next execution slice.
ALTER TABLE edgeai.workflow_run DROP CONSTRAINT workflow_run_mode_check;
ALTER TABLE edgeai.workflow_run DROP CONSTRAINT workflow_run_check;
ALTER TABLE edgeai.workflow_run ADD CONSTRAINT workflow_run_target CHECK(
    (mode IN ('AUTO','REMOTE') AND node_id IS NULL) OR (mode='NODE' AND node_id IS NOT NULL));
ALTER TABLE edgeai.task_attempt DROP CONSTRAINT attempt_target;
ALTER TABLE edgeai.task_attempt ADD CONSTRAINT attempt_target CHECK(
    (mode IN ('AUTO','REMOTE') AND node_id IS NULL) OR (mode='NODE' AND node_id IS NOT NULL));
