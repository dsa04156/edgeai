ALTER TABLE edgeai.task ADD CONSTRAINT task_runtime_identity UNIQUE(id,run_id);
ALTER TABLE edgeai.task_attempt ADD CONSTRAINT attempt_runtime_identity UNIQUE(id,task_id,epoch);

CREATE TABLE edgeai.runtime_instance (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL UNIQUE, task_id uuid NOT NULL, run_id uuid NOT NULL,
    epoch bigint NOT NULL CHECK(epoch BETWEEN 1 AND 9007199254740991),
    namespace varchar(63) NOT NULL CHECK(namespace ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    job_name varchar(63) NOT NULL CHECK(job_name ~ '^edgeai-[a-f0-9-]{36}$'),
    claim_nonce uuid NOT NULL,
    desired_state varchar(8) NOT NULL CHECK(desired_state IN ('RUNNING','STOPPED')),
    observed_state varchar(16) NOT NULL CHECK(observed_state IN ('PENDING','SUBMITTED','RUNNING','TERMINATED')),
    job_uid uuid, producer_pod_uid uuid, node_uid uuid, node_name varchar(253),
    expires_at timestamptz, failure_reason varchar(64),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE(namespace,job_name), UNIQUE(id,attempt_id,task_id,epoch),
    FOREIGN KEY(task_id,run_id) REFERENCES edgeai.task(id,run_id),
    FOREIGN KEY(attempt_id,task_id,epoch) REFERENCES edgeai.task_attempt(id,task_id,epoch),
    CHECK((producer_pod_uid IS NULL AND node_uid IS NULL AND node_name IS NULL)
       OR (producer_pod_uid IS NOT NULL AND node_uid IS NOT NULL AND node_name IS NOT NULL AND job_uid IS NOT NULL)),
    CHECK(observed_state<>'RUNNING' OR producer_pod_uid IS NOT NULL)
);
CREATE INDEX runtime_reconcile ON edgeai.runtime_instance(updated_at,id) WHERE observed_state<>'TERMINATED';

CREATE TABLE edgeai.runtime_command (
    id uuid PRIMARY KEY, runtime_id uuid NOT NULL REFERENCES edgeai.runtime_instance(id),
    kind varchar(8) NOT NULL CHECK(kind IN ('CREATE','DELETE')),
    completed boolean NOT NULL DEFAULT false, attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
    available_at timestamptz NOT NULL, lease_owner uuid, lease_until timestamptz,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE(runtime_id,kind), CHECK((lease_owner IS NULL)=(lease_until IS NULL))
);
CREATE INDEX runtime_command_pending ON edgeai.runtime_command(available_at,id) WHERE NOT completed;

CREATE TABLE edgeai.task_result (
    id uuid PRIMARY KEY, task_id uuid NOT NULL UNIQUE, attempt_id uuid NOT NULL UNIQUE,
    runtime_id uuid NOT NULL UNIQUE, epoch bigint NOT NULL, producer_pod_uid uuid NOT NULL,
    manifest_digest varchar(71) NOT NULL CHECK(manifest_digest ~ '^sha256:[a-f0-9]{64}$'),
    committed boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL,
    FOREIGN KEY(runtime_id,attempt_id,task_id,epoch) REFERENCES edgeai.runtime_instance(id,attempt_id,task_id,epoch)
);
CREATE TABLE edgeai.result_artifact (
    id uuid PRIMARY KEY, result_id uuid NOT NULL REFERENCES edgeai.task_result(id),
    port varchar(100) COLLATE "C" NOT NULL CHECK(port ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    bucket varchar(63) NOT NULL, object_key varchar(512) NOT NULL,
    object_version varchar(1024) NOT NULL CHECK(length(object_version)>0 AND object_version<>'null'),
    sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
    bytes bigint NOT NULL CHECK(bytes BETWEEN 0 AND 268435456), media_type varchar(128) NOT NULL,
    UNIQUE(result_id,port), UNIQUE(bucket,object_key,object_version)
);
CREATE FUNCTION edgeai.protect_task_result() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NOT NEW.committed THEN RETURN NEW; END IF;
    END IF;
    IF TG_OP='UPDATE' THEN
        IF NOT OLD.committed AND NEW.committed AND (to_jsonb(OLD)-'committed')=(to_jsonb(NEW)-'committed') THEN
            IF NOT EXISTS(SELECT 1 FROM edgeai.result_artifact WHERE result_id=NEW.id) THEN
                RAISE EXCEPTION 'Result requires verified artifacts' USING ERRCODE='23514';
            END IF;
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'Committed results are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER task_result_immutable BEFORE INSERT OR UPDATE OR DELETE ON edgeai.task_result
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_task_result();
CREATE TRIGGER task_result_no_truncate BEFORE TRUNCATE ON edgeai.task_result
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_task_result();
CREATE FUNCTION edgeai.protect_result_artifact() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sealed boolean;
BEGIN
    IF TG_OP='INSERT' THEN
        SELECT committed INTO sealed FROM edgeai.task_result WHERE id=NEW.result_id FOR SHARE;
        IF sealed=false THEN RETURN NEW; END IF;
    END IF;
    RAISE EXCEPTION 'Committed artifacts are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER result_artifact_immutable BEFORE INSERT OR UPDATE OR DELETE ON edgeai.result_artifact
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_result_artifact();
CREATE TRIGGER result_artifact_no_truncate BEFORE TRUNCATE ON edgeai.result_artifact
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_result_artifact();
