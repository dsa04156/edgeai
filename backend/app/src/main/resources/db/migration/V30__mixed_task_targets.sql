-- Pin the effective initial provider for every Task, including waiting children.
ALTER TABLE edgeai.task ADD COLUMN initial_vd_id uuid REFERENCES edgeai.virtual_device(id),
    ADD COLUMN initial_remote_provider_key varchar(63),
    ADD COLUMN initial_remote_configuration_digest varchar(71),
    ADD COLUMN initial_remote_source_mode varchar(16);
UPDATE edgeai.task t SET initial_vd_id=w.vd_id,
    initial_remote_provider_key=w.remote_provider_key,
    initial_remote_configuration_digest=w.remote_configuration_digest,
    initial_remote_source_mode=w.remote_source_mode FROM edgeai.workflow_run w WHERE w.id=t.run_id;
ALTER TABLE edgeai.task DROP CONSTRAINT task_initial_target;
ALTER TABLE edgeai.task ADD CONSTRAINT task_initial_target CHECK(
    ((initial_mode='AUTO' AND initial_node_id IS NULL AND initial_vd_id IS NULL) OR
     (initial_mode='NODE' AND initial_node_id IS NOT NULL AND initial_vd_id IS NULL) OR
     (initial_mode='VD' AND initial_node_id IS NULL AND initial_vd_id IS NOT NULL))
        AND initial_remote_provider_key IS NULL AND initial_remote_configuration_digest IS NULL AND initial_remote_source_mode IS NULL OR
    (initial_mode='REMOTE' AND initial_node_id IS NULL AND initial_vd_id IS NULL AND
        edgeai.valid_remote_target(initial_remote_provider_key,initial_remote_configuration_digest,initial_remote_source_mode)));

CREATE OR REPLACE FUNCTION edgeai.protect_run_task_executions() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item record; placement_mode text; fields integer;
BEGIN
    IF TG_OP='UPDATE' THEN
        IF NEW.task_executions IS DISTINCT FROM OLD.task_executions OR NEW.workflow_version_id IS DISTINCT FROM OLD.workflow_version_id THEN
            RAISE EXCEPTION 'Run task execution plan is immutable' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.task_executions IS NULL OR jsonb_typeof(NEW.task_executions)<>'object' THEN
        RAISE EXCEPTION 'Task execution plan must be an object' USING ERRCODE='23514';
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(NEW.task_executions))>128 THEN
        RAISE EXCEPTION 'Task execution plan must be bounded' USING ERRCODE='23514';
    END IF;
    FOR item IN SELECT * FROM jsonb_each(NEW.task_executions) LOOP
        IF NOT EXISTS(SELECT 1 FROM edgeai.task_definition WHERE workflow_version_id=NEW.workflow_version_id AND task_key=item.key)
           OR jsonb_typeof(item.value)<>'object' THEN
            RAISE EXCEPTION 'Task override must reference a published Task and placement' USING ERRCODE='23514';
        END IF;
        placement_mode=item.value->>'mode';
        SELECT count(*) INTO fields FROM jsonb_object_keys(item.value);
        IF placement_mode IS NULL OR placement_mode NOT IN ('AUTO','NODE','VD','REMOTE') OR
           (placement_mode='AUTO' AND fields<>1) OR (placement_mode<>'AUTO' AND fields<>2) THEN
            RAISE EXCEPTION 'Invalid Task placement fields' USING ERRCODE='23514';
        END IF;
        IF placement_mode='NODE' THEN
            IF jsonb_typeof(item.value->'nodeId') IS DISTINCT FROM 'string' OR
               (item.value->>'nodeId') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
                RAISE EXCEPTION 'Task NODE placement requires a canonical Node UID' USING ERRCODE='23514';
            END IF;
            IF NOT EXISTS(SELECT 1 FROM edgeai.execution_node WHERE id=(item.value->>'nodeId')::uuid) THEN
                RAISE EXCEPTION 'Task NODE placement requires a registered Node' USING ERRCODE='23514';
            END IF;
        ELSIF placement_mode='VD' THEN
            IF jsonb_typeof(item.value->'vdId') IS DISTINCT FROM 'string' OR
               (item.value->>'vdId') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
                RAISE EXCEPTION 'Task VD placement requires a canonical VD ID' USING ERRCODE='23514';
            END IF;
            IF NOT EXISTS(SELECT 1 FROM edgeai.virtual_device vd JOIN edgeai.task_definition d ON d.service_profile_version_id=vd.service_profile_version_id
                WHERE vd.id=(item.value->>'vdId')::uuid AND d.workflow_version_id=NEW.workflow_version_id AND d.task_key=item.key) THEN
                RAISE EXCEPTION 'Task VD placement requires its own SERVICE profile' USING ERRCODE='23514';
            END IF;
        ELSIF placement_mode='REMOTE' THEN
            IF jsonb_typeof(item.value->'providerKey') IS DISTINCT FROM 'string' OR
               length(item.value->>'providerKey')>63 OR (item.value->>'providerKey') !~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$' THEN
                RAISE EXCEPTION 'Task REMOTE placement requires a provider key' USING ERRCODE='23514';
            END IF;
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION edgeai.pin_task_initial_execution() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE w edgeai.workflow_run; d edgeai.task_definition; override jsonb;
    expected_mode text; expected_node uuid; expected_vd uuid;
    expected_key text; expected_digest text; expected_source text;
BEGIN
    IF TG_OP='UPDATE' THEN
        IF (NEW.run_id,NEW.workflow_version_id,NEW.definition_id,NEW.initial_mode,NEW.initial_node_id,NEW.initial_vd_id,
            NEW.initial_remote_provider_key,NEW.initial_remote_configuration_digest,NEW.initial_remote_source_mode) IS DISTINCT FROM
           (OLD.run_id,OLD.workflow_version_id,OLD.definition_id,OLD.initial_mode,OLD.initial_node_id,OLD.initial_vd_id,
            OLD.initial_remote_provider_key,OLD.initial_remote_configuration_digest,OLD.initial_remote_source_mode) THEN
            RAISE EXCEPTION 'Task identity and initial placement are immutable' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO w FROM edgeai.workflow_run WHERE id=NEW.run_id AND workflow_version_id=NEW.workflow_version_id FOR UPDATE;
    SELECT * INTO d FROM edgeai.task_definition WHERE id=NEW.definition_id AND workflow_version_id=NEW.workflow_version_id;
    IF w.id IS NULL OR d.id IS NULL THEN
        RAISE EXCEPTION 'Task placement requires its own Run and definition' USING ERRCODE='23514';
    END IF;
    override=w.task_executions->d.task_key;
    expected_mode=w.mode;expected_node=w.node_id;expected_vd=w.vd_id;
    expected_key=w.remote_provider_key;expected_digest=w.remote_configuration_digest;expected_source=w.remote_source_mode;
    IF override IS NOT NULL THEN
        expected_mode=override->>'mode';expected_node=(override->>'nodeId')::uuid;expected_vd=(override->>'vdId')::uuid;
        expected_key=NULL;expected_digest=NULL;expected_source=NULL;
        IF expected_mode='REMOTE' THEN
            IF NEW.initial_remote_provider_key IS DISTINCT FROM override->>'providerKey' OR
                NOT edgeai.valid_remote_target(NEW.initial_remote_provider_key,NEW.initial_remote_configuration_digest,NEW.initial_remote_source_mode) THEN
                RAISE EXCEPTION 'Task REMOTE placement requires the selected provider binding' USING ERRCODE='23514';
            END IF;
            expected_key=NEW.initial_remote_provider_key;expected_digest=NEW.initial_remote_configuration_digest;expected_source=NEW.initial_remote_source_mode;
        END IF;
    END IF;
    IF (NEW.initial_mode IS NOT NULL AND NEW.initial_mode<>expected_mode) OR
       (NEW.initial_node_id IS NOT NULL AND NEW.initial_node_id IS DISTINCT FROM expected_node) OR
       (NEW.initial_vd_id IS NOT NULL AND NEW.initial_vd_id IS DISTINCT FROM expected_vd) OR
       (NEW.initial_remote_provider_key IS NOT NULL AND NEW.initial_remote_provider_key IS DISTINCT FROM expected_key) OR
       (NEW.initial_remote_configuration_digest IS NOT NULL AND NEW.initial_remote_configuration_digest IS DISTINCT FROM expected_digest) OR
       (NEW.initial_remote_source_mode IS NOT NULL AND NEW.initial_remote_source_mode IS DISTINCT FROM expected_source) THEN
        RAISE EXCEPTION 'Task placement must match its pinned Run plan' USING ERRCODE='23514';
    END IF;
    IF expected_mode='VD' AND NOT EXISTS(SELECT 1 FROM edgeai.virtual_device WHERE id=expected_vd AND service_profile_version_id=d.service_profile_version_id) THEN
        RAISE EXCEPTION 'Task VD placement requires its own SERVICE profile' USING ERRCODE='23514';
    END IF;
    NEW.initial_mode=expected_mode;NEW.initial_node_id=expected_node;NEW.initial_vd_id=expected_vd;
    NEW.initial_remote_provider_key=expected_key;NEW.initial_remote_configuration_digest=expected_digest;NEW.initial_remote_source_mode=expected_source;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION edgeai.check_initial_attempt_placement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.cause='INITIAL' AND (NEW.number<>1 OR NEW.epoch<>1 OR NOT EXISTS(
        SELECT 1 FROM edgeai.task t WHERE t.id=NEW.task_id AND
            (t.initial_mode,t.initial_node_id,t.initial_vd_id,t.initial_remote_provider_key,t.initial_remote_configuration_digest,t.initial_remote_source_mode)
            IS NOT DISTINCT FROM (NEW.mode,NEW.node_id,NEW.vd_id,NEW.remote_provider_key,NEW.remote_configuration_digest,NEW.remote_source_mode))) THEN
        RAISE EXCEPTION 'INITIAL Attempt must use its Task placement' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Each allocation belongs to its Task target, not the Run default.
CREATE OR REPLACE FUNCTION edgeai.protect_vd_task_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE vr edgeai.vd_runtime; r edgeai.runtime_instance; capacity integer; last_sequence bigint;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD Task allocation history must be retained' USING ERRCODE='23514';
    END IF;
    PERFORM id FROM edgeai.virtual_device WHERE id=NEW.vd_id FOR NO KEY UPDATE;
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
                WHERE a.id=r.attempt_id AND a.mode='VD' AND a.vd_id=NEW.vd_id AND t.initial_vd_id=NEW.vd_id
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

-- Serialize VD mutations while permitting immutable target foreign-key references.
CREATE OR REPLACE FUNCTION edgeai.protect_vd_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE vd edgeai.virtual_device; device edgeai.device; requirement jsonb;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD source history must be retained' USING ERRCODE='23514';
    END IF;
    SELECT * INTO vd FROM edgeai.virtual_device WHERE id=NEW.vd_id FOR NO KEY UPDATE;
    IF TG_OP='UPDATE' THEN
        IF (to_jsonb(NEW)-'closed_revision'-'closed_at') IS DISTINCT FROM (to_jsonb(OLD)-'closed_revision'-'closed_at')
            OR OLD.closed_at IS NOT NULL OR NEW.closed_at IS NULL OR NEW.closed_revision IS DISTINCT FROM vd.revision THEN
            RAISE EXCEPTION 'VD source can only be closed once at the current revision' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO device FROM edgeai.device WHERE id=NEW.device_id FOR UPDATE;
    SELECT spec->'sources'->NEW.source_key INTO requirement FROM edgeai.profile_version WHERE id=vd.profile_version_id;
    IF vd.state IS DISTINCT FROM 'REGISTERED' OR vd.revision IS DISTINCT FROM NEW.opened_revision
        OR NEW.closed_at IS NOT NULL OR device.state IS DISTINCT FROM 'ACTIVE'
        OR lower(requirement->>'deviceProfileVersionId') IS DISTINCT FROM NEW.device_profile_version_id::text
        OR NOT coalesce((requirement->'sourceModes') ? NEW.source_mode,false) THEN
        RAISE EXCEPTION 'VD source must match the declared profile and an active Device' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Serialize VD mutations while permitting immutable target foreign-key references.
CREATE OR REPLACE FUNCTION edgeai.protect_vd_runtime() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE vd edgeai.virtual_device; sources jsonb; profile jsonb; expected_generation bigint;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD runtime history must be retained' USING ERRCODE='23514';
    END IF;
    SELECT * INTO vd FROM edgeai.virtual_device WHERE id=NEW.vd_id FOR NO KEY UPDATE;
    IF TG_OP='INSERT' THEN
        SELECT coalesce(max(generation),0)+1 INTO expected_generation FROM edgeai.vd_runtime WHERE vd_id=NEW.vd_id;
        SELECT coalesce(jsonb_object_agg(source_key,id::text),'{}'::jsonb) INTO sources
            FROM edgeai.vd_source_binding WHERE vd_id=NEW.vd_id AND closed_at IS NULL;
        SELECT spec INTO profile FROM edgeai.profile_version WHERE id=vd.profile_version_id;
        IF vd.state IS DISTINCT FROM 'REGISTERED' OR NEW.requested_revision IS DISTINCT FROM vd.revision
            OR NEW.generation<>expected_generation OR NEW.desired_state<>'RUNNING' OR NEW.observed_state<>'PENDING'
            OR NEW.pod_uid IS NOT NULL OR NEW.session_id IS NOT NULL
            OR NEW.configuration->'sources' IS DISTINCT FROM sources
            OR NEW.configuration->>'serviceProfileVersionId' IS DISTINCT FROM vd.service_profile_version_id::text
            OR NEW.configuration->>'namespace' IS DISTINCT FROM NEW.namespace
            OR NEW.configuration->>'placementMode' IS DISTINCT FROM vd.placement_mode
            OR NEW.configuration->>'targetNodeId' IS DISTINCT FROM vd.node_id::text
            OR NEW.configuration->>'targetNodeName' IS DISTINCT FROM (SELECT name FROM edgeai.execution_node WHERE id=vd.node_id)
            OR NEW.configuration->'maxConcurrentTasks' IS DISTINCT FROM profile->'runtime'->'maxConcurrentTasks'
            OR NEW.configuration->'startupSeconds' IS DISTINCT FROM profile->'runtime'->'startupTimeoutSeconds'
            OR NEW.configuration->'drainSeconds' IS DISTINCT FROM profile->'runtime'->'drainTimeoutSeconds' THEN
            RAISE EXCEPTION 'VD runtime must snapshot the current registered configuration and next generation' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (NEW.id,NEW.vd_id,NEW.generation,NEW.requested_revision,NEW.configuration,NEW.configuration_digest,
            NEW.namespace,NEW.pod_name,NEW.claim_nonce,NEW.startup_deadline,NEW.created_at)
            IS DISTINCT FROM (OLD.id,OLD.vd_id,OLD.generation,OLD.requested_revision,OLD.configuration,OLD.configuration_digest,
            OLD.namespace,OLD.pod_name,OLD.claim_nonce,OLD.startup_deadline,OLD.created_at)
            OR NEW.updated_at<OLD.updated_at
            OR (OLD.pod_uid IS NOT NULL AND NEW.pod_uid IS DISTINCT FROM OLD.pod_uid)
            OR (OLD.session_id IS NOT NULL AND (NEW.session_id,NEW.node_uid,NEW.node_name) IS DISTINCT FROM (OLD.session_id,OLD.node_uid,OLD.node_name))
            OR (OLD.desired_state='STOPPED' AND NEW.desired_state<>'STOPPED')
            OR (OLD.desired_state='DRAINING' AND NEW.desired_state='RUNNING')
            OR (OLD.observed_state='TERMINATED' AND NEW.observed_state<>'TERMINATED')
            OR (OLD.drain_deadline IS NOT NULL AND NEW.drain_deadline IS DISTINCT FROM OLD.drain_deadline) THEN
            RAISE EXCEPTION 'VD runtime identity, fencing and terminal history are immutable' USING ERRCODE='23514';
        END IF;
        IF OLD.ready_at IS NOT NULL AND NEW.ready_at IS DISTINCT FROM OLD.ready_at THEN
            RAISE EXCEPTION 'First readiness is immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.observed_state='TERMINATED' AND OLD.observed_state<>'TERMINATED' AND NOT EXISTS (
            SELECT 1 FROM edgeai.vd_runtime_command WHERE runtime_id=NEW.id AND kind='CREATE' AND completed
        ) THEN
            RAISE EXCEPTION 'Creation must resolve before termination is confirmed' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- Serialize VD mutations while permitting immutable target foreign-key references.
CREATE OR REPLACE FUNCTION edgeai.protect_vd_runtime_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE vd edgeai.virtual_device; runtime edgeai.vd_runtime;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD runtime binding history must be retained' USING ERRCODE='23514';
    END IF;
    SELECT * INTO vd FROM edgeai.virtual_device WHERE id=NEW.vd_id FOR NO KEY UPDATE;
    SELECT * INTO runtime FROM edgeai.vd_runtime WHERE id=NEW.runtime_id;
    IF TG_OP='INSERT' THEN
        IF NEW.vd_id IS DISTINCT FROM runtime.vd_id OR NEW.opened_revision IS DISTINCT FROM runtime.requested_revision
            OR NEW.closed_at IS NOT NULL OR runtime.observed_state<>'PENDING' THEN
            RAISE EXCEPTION 'Runtime binding must open with its own new runtime' USING ERRCODE='23514';
        END IF;
    ELSIF (to_jsonb(NEW)-'closed_at'-'closed_revision') IS DISTINCT FROM (to_jsonb(OLD)-'closed_at'-'closed_revision')
        OR OLD.closed_at IS NOT NULL OR NEW.closed_at IS NULL OR NEW.closed_revision IS DISTINCT FROM vd.revision
        OR runtime.observed_state IS DISTINCT FROM 'TERMINATED' THEN
        RAISE EXCEPTION 'Runtime binding closes only once after confirmed termination' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
