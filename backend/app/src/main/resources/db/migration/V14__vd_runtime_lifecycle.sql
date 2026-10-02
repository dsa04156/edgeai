CREATE TABLE edgeai.vd_runtime (
    id uuid PRIMARY KEY, vd_id uuid NOT NULL REFERENCES edgeai.virtual_device(id),
    generation bigint NOT NULL CHECK (generation BETWEEN 1 AND 9007199254740991),
    requested_revision bigint NOT NULL CHECK (requested_revision BETWEEN 0 AND 9007199254740991),
    configuration jsonb NOT NULL CHECK (jsonb_typeof(configuration)='object'),
    configuration_digest varchar(71) NOT NULL CHECK (configuration_digest ~ '^sha256:[a-f0-9]{64}$'),
    namespace varchar(63) NOT NULL CHECK (namespace ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    pod_name varchar(63) NOT NULL CHECK (pod_name='edgeai-vd-'||id::text), claim_nonce uuid NOT NULL,
    desired_state varchar(8) NOT NULL CHECK (desired_state IN ('RUNNING','DRAINING','STOPPED')),
    observed_state varchar(16) NOT NULL CHECK (observed_state IN ('PENDING','SUBMITTED','READY','UNREADY','TERMINATED')),
    pod_uid uuid, node_uid uuid, node_name varchar(253), session_id uuid, lease_until timestamptz, ready_at timestamptz,
    startup_deadline timestamptz NOT NULL, drain_deadline timestamptz,
    failure_reason varchar(64) CHECK (failure_reason ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE (id,vd_id), UNIQUE (vd_id,generation), UNIQUE (namespace,pod_name),
    CHECK (startup_deadline>created_at AND updated_at>=created_at),
    CHECK ((session_id IS NULL AND node_uid IS NULL AND node_name IS NULL AND lease_until IS NULL)
        OR (session_id IS NOT NULL AND node_uid IS NOT NULL AND node_name IS NOT NULL AND lease_until IS NOT NULL AND pod_uid IS NOT NULL)),
    CHECK (observed_state<>'READY' OR (desired_state='RUNNING' AND session_id IS NOT NULL AND ready_at IS NOT NULL)),
    CHECK (ready_at IS NULL OR (ready_at>=created_at AND session_id IS NOT NULL)),
    CHECK (observed_state<>'TERMINATED' OR desired_state='STOPPED'),
    CHECK (desired_state<>'DRAINING' OR drain_deadline IS NOT NULL)
);
CREATE UNIQUE INDEX vd_one_live_runtime ON edgeai.vd_runtime(vd_id) WHERE observed_state<>'TERMINATED';
CREATE INDEX vd_runtime_reconcile ON edgeai.vd_runtime(namespace,updated_at,id) WHERE observed_state<>'TERMINATED';

CREATE TABLE edgeai.vd_runtime_binding (
    id uuid PRIMARY KEY, vd_id uuid NOT NULL REFERENCES edgeai.virtual_device(id), runtime_id uuid NOT NULL UNIQUE,
    opened_revision bigint NOT NULL CHECK (opened_revision BETWEEN 0 AND 9007199254740991),
    closed_revision bigint CHECK (closed_revision BETWEEN 0 AND 9007199254740991),
    opened_at timestamptz NOT NULL, closed_at timestamptz,
    FOREIGN KEY (runtime_id,vd_id) REFERENCES edgeai.vd_runtime(id,vd_id),
    CHECK ((closed_at IS NULL)=(closed_revision IS NULL)),
    CHECK (closed_at IS NULL OR (closed_at>=opened_at AND closed_revision>=opened_revision))
);
CREATE UNIQUE INDEX vd_one_open_runtime_binding ON edgeai.vd_runtime_binding(vd_id) WHERE closed_at IS NULL;

CREATE TABLE edgeai.vd_operation (
    id uuid PRIMARY KEY, vd_id uuid NOT NULL REFERENCES edgeai.virtual_device(id),
    request_key varchar(128) COLLATE "C" NOT NULL CHECK (length(request_key)>0),
    request_digest varchar(71) NOT NULL CHECK (request_digest ~ '^sha256:[a-f0-9]{64}$'),
    kind varchar(16) NOT NULL CHECK (kind IN ('PROVISION','REPLACE','DRAIN')),
    requested_revision bigint NOT NULL CHECK (requested_revision BETWEEN 0 AND 9007199254740991),
    configuration jsonb, configuration_digest varchar(71),
    source_runtime_id uuid, target_runtime_id uuid,
    state varchar(16) NOT NULL CHECK (state IN ('RUNNING','SUCCEEDED','FAILED','SUPERSEDED')),
    reason varchar(64) CHECK (reason ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, finished_at timestamptz,
    UNIQUE (vd_id,request_key),
    FOREIGN KEY (source_runtime_id,vd_id) REFERENCES edgeai.vd_runtime(id,vd_id),
    FOREIGN KEY (target_runtime_id,vd_id) REFERENCES edgeai.vd_runtime(id,vd_id),
    CHECK ((state='RUNNING')=(finished_at IS NULL)),
    CHECK ((kind='DRAIN' AND configuration IS NULL AND configuration_digest IS NULL AND target_runtime_id IS NULL)
        OR (kind<>'DRAIN' AND configuration IS NOT NULL AND configuration_digest IS NOT NULL
            AND jsonb_typeof(configuration)='object' AND configuration_digest ~ '^sha256:[a-f0-9]{64}$')),
    CHECK (kind<>'REPLACE' OR source_runtime_id IS NOT NULL),
    CHECK (updated_at>=created_at AND (finished_at IS NULL OR finished_at>=created_at))
);
CREATE UNIQUE INDEX vd_one_pending_operation ON edgeai.vd_operation(vd_id) WHERE state='RUNNING';

CREATE TABLE edgeai.vd_runtime_command (
    id uuid PRIMARY KEY, runtime_id uuid NOT NULL REFERENCES edgeai.vd_runtime(id),
    kind varchar(8) NOT NULL CHECK (kind IN ('CREATE','DELETE')),
    completed boolean NOT NULL DEFAULT false, attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0),
    available_at timestamptz NOT NULL, lease_owner uuid, lease_until timestamptz,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE (runtime_id,kind), CHECK ((lease_owner IS NULL)=(lease_until IS NULL))
);
CREATE INDEX vd_command_pending ON edgeai.vd_runtime_command(available_at,id) WHERE NOT completed;

CREATE FUNCTION edgeai.protect_vd_runtime() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE vd edgeai.virtual_device; sources jsonb; profile jsonb; expected_generation bigint;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD runtime history must be retained' USING ERRCODE='23514';
    END IF;
    SELECT * INTO vd FROM edgeai.virtual_device WHERE id=NEW.vd_id FOR UPDATE;
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
CREATE TRIGGER vd_runtime_protected BEFORE INSERT OR UPDATE OR DELETE ON edgeai.vd_runtime
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_vd_runtime();
CREATE TRIGGER vd_runtime_no_truncate BEFORE TRUNCATE ON edgeai.vd_runtime
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_vd_runtime();

CREATE FUNCTION edgeai.protect_vd_runtime_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE vd edgeai.virtual_device; runtime edgeai.vd_runtime;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD runtime binding history must be retained' USING ERRCODE='23514';
    END IF;
    SELECT * INTO vd FROM edgeai.virtual_device WHERE id=NEW.vd_id FOR UPDATE;
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
CREATE TRIGGER vd_runtime_binding_protected BEFORE INSERT OR UPDATE OR DELETE ON edgeai.vd_runtime_binding
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_vd_runtime_binding();
CREATE TRIGGER vd_runtime_binding_no_truncate BEFORE TRUNCATE ON edgeai.vd_runtime_binding
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_vd_runtime_binding();

CREATE FUNCTION edgeai.protect_vd_operation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source edgeai.vd_runtime; target edgeai.vd_runtime;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD operations must be retained' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (OLD.state<>'RUNNING' OR (to_jsonb(NEW)-'target_runtime_id'-'state'-'reason'-'updated_at'-'finished_at')
        IS DISTINCT FROM (to_jsonb(OLD)-'target_runtime_id'-'state'-'reason'-'updated_at'-'finished_at')
        OR (OLD.target_runtime_id IS NOT NULL AND NEW.target_runtime_id IS DISTINCT FROM OLD.target_runtime_id)
        OR NEW.updated_at<OLD.updated_at) THEN
        RAISE EXCEPTION 'Operation request and terminal outcome are immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.state='SUCCEEDED' THEN
        SELECT * INTO source FROM edgeai.vd_runtime WHERE id=NEW.source_runtime_id;
        SELECT * INTO target FROM edgeai.vd_runtime WHERE id=NEW.target_runtime_id;
        IF (NEW.source_runtime_id IS NOT NULL AND source.observed_state IS DISTINCT FROM 'TERMINATED')
            OR (NEW.kind<>'DRAIN' AND (target.observed_state IS DISTINCT FROM 'READY'
                OR target.desired_state IS DISTINCT FROM 'RUNNING' OR target.lease_until<=NEW.finished_at)) THEN
            RAISE EXCEPTION 'Operation success requires observed termination or attested readiness' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER vd_operation_protected BEFORE INSERT OR UPDATE OR DELETE ON edgeai.vd_operation
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_vd_operation();
CREATE TRIGGER vd_operation_no_truncate BEFORE TRUNCATE ON edgeai.vd_operation
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_vd_operation();

CREATE FUNCTION edgeai.check_vd_runtime_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; runtime edgeai.vd_runtime; binding edgeai.vd_runtime_binding;
BEGIN
    IF TG_TABLE_NAME='vd_runtime' THEN target:=NEW.id; ELSE target:=NEW.runtime_id; END IF;
    SELECT * INTO runtime FROM edgeai.vd_runtime WHERE id=target;
    SELECT * INTO binding FROM edgeai.vd_runtime_binding WHERE runtime_id=target;
    IF binding.id IS NULL OR ((runtime.observed_state='TERMINATED') IS DISTINCT FROM (binding.closed_at IS NOT NULL)) THEN
        RAISE EXCEPTION 'Runtime lifecycle and binding history must agree' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER vd_runtime_binding_complete AFTER INSERT OR UPDATE ON edgeai.vd_runtime
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION edgeai.check_vd_runtime_binding();
CREATE CONSTRAINT TRIGGER vd_runtime_binding_state AFTER INSERT OR UPDATE ON edgeai.vd_runtime_binding
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION edgeai.check_vd_runtime_binding();

-- An old source remains in use while its captured runtime is draining.
CREATE OR REPLACE FUNCTION edgeai.guard_device_vd_release() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state='RELEASED' AND EXISTS (
        SELECT 1 FROM edgeai.vd_source_binding b WHERE b.device_id=NEW.id AND
        (b.closed_at IS NULL OR EXISTS (SELECT 1 FROM edgeai.vd_runtime r WHERE r.vd_id=b.vd_id
            AND r.observed_state<>'TERMINATED' AND r.configuration->'sources'->>b.source_key=b.id::text))
    ) THEN
        RAISE EXCEPTION 'Device is still a registered or running VD source' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
