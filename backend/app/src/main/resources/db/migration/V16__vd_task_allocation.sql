-- A VD Task runs inside an existing supervisor Pod. It must never become a synthetic Job.
ALTER TABLE edgeai.workflow_run ADD COLUMN vd_id uuid REFERENCES edgeai.virtual_device(id);
ALTER TABLE edgeai.task_attempt ADD COLUMN vd_id uuid REFERENCES edgeai.virtual_device(id);
ALTER TABLE edgeai.workflow_run DROP CONSTRAINT workflow_run_target;
ALTER TABLE edgeai.workflow_run ADD CONSTRAINT workflow_run_target CHECK(
    (mode IN ('AUTO','REMOTE') AND node_id IS NULL AND vd_id IS NULL) OR
    (mode='NODE' AND node_id IS NOT NULL AND vd_id IS NULL) OR
    (mode='VD' AND node_id IS NULL AND vd_id IS NOT NULL));
ALTER TABLE edgeai.task_attempt DROP CONSTRAINT attempt_target;
ALTER TABLE edgeai.task_attempt ADD CONSTRAINT attempt_target CHECK(
    (mode IN ('AUTO','REMOTE') AND node_id IS NULL AND vd_id IS NULL) OR
    (mode='NODE' AND node_id IS NOT NULL AND vd_id IS NULL) OR
    (mode='VD' AND node_id IS NULL AND vd_id IS NOT NULL));

CREATE OR REPLACE FUNCTION edgeai.protect_execution_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW)->'mode') IS DISTINCT FROM (to_jsonb(OLD)->'mode') OR
       (to_jsonb(NEW)->'node_id') IS DISTINCT FROM (to_jsonb(OLD)->'node_id') OR
       (to_jsonb(NEW)->'vd_id') IS DISTINCT FROM (to_jsonb(OLD)->'vd_id') OR
       (to_jsonb(NEW)->'target_node_id') IS DISTINCT FROM (to_jsonb(OLD)->'target_node_id') OR
       NEW.remote_provider_key IS DISTINCT FROM OLD.remote_provider_key OR
       NEW.remote_configuration_digest IS DISTINCT FROM OLD.remote_configuration_digest OR
       NEW.remote_source_mode IS DISTINCT FROM OLD.remote_source_mode THEN
        RAISE EXCEPTION 'Execution target is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE edgeai.runtime_instance ADD COLUMN vd_id uuid REFERENCES edgeai.virtual_device(id),
    ADD CONSTRAINT runtime_vd_identity UNIQUE(id,vd_id);
ALTER TABLE edgeai.runtime_instance DROP CONSTRAINT runtime_producer_kind;
ALTER TABLE edgeai.runtime_instance DROP CONSTRAINT runtime_instance_check;
ALTER TABLE edgeai.runtime_instance ADD CONSTRAINT runtime_producer_kind CHECK(
    (vd_id IS NULL AND remote_allocation_id IS NULL AND job_name IS NOT NULL
        AND ((producer_pod_uid IS NULL AND node_uid IS NULL AND node_name IS NULL) OR
             (producer_pod_uid IS NOT NULL AND node_uid IS NOT NULL AND node_name IS NOT NULL AND job_uid IS NOT NULL))
        AND (observed_state<>'RUNNING' OR producer_pod_uid IS NOT NULL)) OR
    (vd_id IS NULL AND remote_allocation_id IS NOT NULL AND job_name IS NULL AND job_uid IS NULL
        AND producer_pod_uid IS NULL AND node_uid IS NULL AND node_name IS NULL AND expires_at IS NOT NULL) OR
    (vd_id IS NOT NULL AND remote_allocation_id IS NULL AND job_name IS NULL AND job_uid IS NULL AND expires_at IS NOT NULL
        AND ((producer_pod_uid IS NULL AND node_uid IS NULL AND node_name IS NULL) OR
             (producer_pod_uid IS NOT NULL AND node_uid IS NOT NULL AND node_name IS NOT NULL))
        AND (observed_state<>'RUNNING' OR producer_pod_uid IS NOT NULL)));

-- Recompute stored provider discriminators while preserving existing rows and FK validation.
ALTER TABLE edgeai.task_result DROP CONSTRAINT result_runtime_kind;
ALTER TABLE edgeai.runtime_instance DROP CONSTRAINT runtime_kind_identity;
ALTER TABLE edgeai.runtime_instance DROP COLUMN runtime_kind;
ALTER TABLE edgeai.runtime_instance ADD COLUMN runtime_kind varchar(10) GENERATED ALWAYS AS
    (CASE WHEN vd_id IS NOT NULL THEN 'VD' WHEN remote_allocation_id IS NOT NULL THEN 'REMOTE' ELSE 'KUBERNETES' END) STORED,
    ADD CONSTRAINT runtime_kind_identity UNIQUE(id,runtime_kind);

ALTER TABLE edgeai.vd_runtime ADD CONSTRAINT vd_allocation_identity UNIQUE(id,vd_id,generation,session_id,pod_uid);
CREATE TABLE edgeai.vd_task_allocation (
    id uuid PRIMARY KEY, runtime_id uuid NOT NULL UNIQUE, vd_id uuid NOT NULL,
    vd_runtime_id uuid NOT NULL, generation bigint NOT NULL, session_id uuid NOT NULL, pod_uid uuid NOT NULL,
    slot integer NOT NULL CHECK(slot BETWEEN 1 AND 16),
    assigned_sequence bigint NOT NULL CHECK(assigned_sequence BETWEEN 0 AND 9007199254740991),
    assigned_at timestamptz NOT NULL, closed_at timestamptz, close_reason varchar(16),
    completion_sequence bigint CHECK(completion_sequence BETWEEN assigned_sequence AND 9007199254740991),
    exit_code integer CHECK(exit_code BETWEEN -64 AND 255),
    UNIQUE(runtime_id,pod_uid,vd_runtime_id),
    FOREIGN KEY(runtime_id,vd_id) REFERENCES edgeai.runtime_instance(id,vd_id),
    FOREIGN KEY(vd_runtime_id,vd_id,generation,session_id,pod_uid)
        REFERENCES edgeai.vd_runtime(id,vd_id,generation,session_id,pod_uid),
    CHECK((closed_at IS NULL AND close_reason IS NULL AND completion_sequence IS NULL AND exit_code IS NULL) OR
        (closed_at IS NOT NULL AND close_reason IS NOT NULL AND closed_at>=assigned_at AND close_reason='PROCESS_EXIT' AND completion_sequence IS NOT NULL AND exit_code IS NOT NULL) OR
        (closed_at IS NOT NULL AND close_reason IS NOT NULL AND closed_at>=assigned_at AND close_reason='POD_GONE' AND completion_sequence IS NULL AND exit_code IS NULL))
);
CREATE UNIQUE INDEX vd_one_task_per_slot ON edgeai.vd_task_allocation(vd_runtime_id,slot) WHERE closed_at IS NULL;
CREATE INDEX vd_allocation_history ON edgeai.vd_task_allocation(vd_runtime_id,assigned_sequence,runtime_id);
CREATE INDEX vd_task_queue ON edgeai.runtime_instance(vd_id,created_at,id)
    WHERE vd_id IS NOT NULL AND desired_state='RUNNING' AND observed_state='PENDING';

CREATE FUNCTION edgeai.protect_vd_task_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE vr edgeai.vd_runtime; r edgeai.runtime_instance; capacity integer; last_sequence bigint;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD Task allocation history must be retained' USING ERRCODE='23514';
    END IF;
    PERFORM id FROM edgeai.virtual_device WHERE id=NEW.vd_id FOR UPDATE;
    SELECT * INTO vr FROM edgeai.vd_runtime WHERE id=NEW.vd_runtime_id;
    SELECT * INTO r FROM edgeai.runtime_instance WHERE id=NEW.runtime_id;
    IF TG_OP='INSERT' THEN
        capacity=(vr.configuration->>'maxConcurrentTasks')::integer;
        SELECT sequence INTO last_sequence FROM edgeai.vd_runtime_poll WHERE runtime_id=vr.id;
        IF vr.id IS NULL OR r.id IS NULL OR NEW.slot>capacity
            OR (NEW.vd_id,NEW.generation,NEW.session_id,NEW.pod_uid) IS DISTINCT FROM (vr.vd_id,vr.generation,vr.session_id,vr.pod_uid)
            OR vr.desired_state<>'RUNNING' OR vr.observed_state<>'READY' OR vr.lease_until<=NEW.assigned_at
            OR r.vd_id IS DISTINCT FROM vr.vd_id OR r.namespace<>vr.namespace
            OR r.desired_state<>'RUNNING' OR r.observed_state<>'PENDING' OR r.expires_at<=NEW.assigned_at
            OR NEW.assigned_at<r.created_at OR NEW.assigned_at<vr.created_at
            OR NEW.assigned_sequence NOT IN (coalesce(last_sequence,-1)+1,coalesce(last_sequence,-1))
            OR NEW.closed_at IS NOT NULL
            OR NOT EXISTS(SELECT 1 FROM edgeai.task_attempt a JOIN edgeai.task t ON t.id=a.task_id
                JOIN edgeai.workflow_run w ON w.id=t.run_id JOIN edgeai.task_definition d ON d.id=t.definition_id
                JOIN edgeai.virtual_device vd ON vd.id=NEW.vd_id
                WHERE a.id=r.attempt_id AND a.mode='VD' AND a.vd_id=NEW.vd_id AND w.vd_id=NEW.vd_id
                  AND a.state='DISPATCHING' AND t.state='RUNNING' AND w.state='RUNNING'
                  AND vd.state='REGISTERED' AND d.service_profile_version_id=vd.service_profile_version_id) THEN
            RAISE EXCEPTION 'VD Task allocation requires current Ready authority and matching work' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (to_jsonb(OLD)-ARRAY['closed_at','close_reason','completion_sequence','exit_code']) IS DISTINCT FROM
           (to_jsonb(NEW)-ARRAY['closed_at','close_reason','completion_sequence','exit_code']) OR
           (OLD.closed_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
            RAISE EXCEPTION 'VD allocation identity and closure are immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.closed_at IS NOT NULL AND OLD.closed_at IS NULL AND
           (r.desired_state<>'STOPPED' OR r.observed_state<>'TERMINATED' OR
            (NEW.close_reason='POD_GONE' AND vr.observed_state<>'TERMINATED')) THEN
            RAISE EXCEPTION 'VD slot release requires confirmed process or Pod termination' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER vd_task_allocation_protected BEFORE INSERT OR UPDATE OR DELETE ON edgeai.vd_task_allocation
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_vd_task_allocation();
CREATE TRIGGER vd_task_allocation_no_truncate BEFORE TRUNCATE ON edgeai.vd_task_allocation
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_vd_task_allocation();

CREATE FUNCTION edgeai.protect_task_runtime_vd() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND NEW.vd_id IS DISTINCT FROM OLD.vd_id THEN
        RAISE EXCEPTION 'Task runtime VD target is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.vd_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM edgeai.task_attempt a WHERE a.id=NEW.attempt_id AND a.mode='VD' AND a.vd_id=NEW.vd_id) THEN
        RAISE EXCEPTION 'Task runtime must use its pinned VD Attempt' USING ERRCODE='23514';
    END IF;
    IF NEW.vd_id IS NULL AND EXISTS(SELECT 1 FROM edgeai.task_attempt a WHERE a.id=NEW.attempt_id AND a.mode='VD') THEN
        RAISE EXCEPTION 'VD Attempt cannot become a Job or Remote runtime' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER task_runtime_vd_protected BEFORE INSERT OR UPDATE ON edgeai.runtime_instance
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_task_runtime_vd();

ALTER TABLE edgeai.task_result ADD COLUMN vd_runtime_id uuid,
    ADD CONSTRAINT result_vd_allocation FOREIGN KEY(runtime_id,producer_pod_uid,vd_runtime_id)
        REFERENCES edgeai.vd_task_allocation(runtime_id,pod_uid,vd_runtime_id);
ALTER TABLE edgeai.task_result DROP COLUMN producer_kind;
ALTER TABLE edgeai.task_result ADD COLUMN producer_kind varchar(10) GENERATED ALWAYS AS
    (CASE WHEN vd_runtime_id IS NOT NULL THEN 'VD' WHEN producer_pod_uid IS NULL THEN 'REMOTE' ELSE 'KUBERNETES' END) STORED,
    ADD CONSTRAINT result_runtime_kind FOREIGN KEY(runtime_id,producer_kind) REFERENCES edgeai.runtime_instance(id,runtime_kind),
    ADD CONSTRAINT result_vd_kind CHECK(vd_runtime_id IS NULL OR (producer_pod_uid IS NOT NULL AND remote_allocation_id IS NULL));
