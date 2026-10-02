-- Data-plane payloads and MQTT credentials are not stored in these control tables.
ALTER TABLE edgeai.task ADD CONSTRAINT stream_task_run_identity UNIQUE(id,run_id);
ALTER TABLE edgeai.device_session ADD CONSTRAINT stream_device_session_identity UNIQUE(id,device_id,epoch);

CREATE TABLE edgeai.data_route (
    id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES edgeai.workflow_run(id),
    source_task_id uuid, source_device_id uuid REFERENCES edgeai.device(id),
    source_profile_version_id uuid NOT NULL REFERENCES edgeai.profile_version(id),
    source_mode varchar(16), source_port varchar(100) NOT NULL,
    consumer_task_id uuid NOT NULL, consumer_port varchar(100) NOT NULL,
    media_type varchar(127) NOT NULL, max_payload_bytes integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT stream_route_source CHECK(
        (source_task_id IS NOT NULL AND source_device_id IS NULL AND source_mode IS NULL AND source_task_id<>consumer_task_id) OR
        (source_task_id IS NULL AND source_device_id IS NOT NULL AND source_mode IS NOT NULL AND source_mode IN ('LIVE','REPLAY','SYNTHETIC'))),
    CONSTRAINT stream_route_ports CHECK(source_port ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$' AND consumer_port ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    CONSTRAINT stream_route_media CHECK(media_type ~ '^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$' AND max_payload_bytes BETWEEN 1 AND 262144),
    FOREIGN KEY(source_task_id,run_id) REFERENCES edgeai.task(id,run_id),
    FOREIGN KEY(consumer_task_id,run_id) REFERENCES edgeai.task(id,run_id),
    UNIQUE(run_id,consumer_task_id,consumer_port), UNIQUE(id,run_id,consumer_task_id)
);
CREATE INDEX data_route_run ON edgeai.data_route(run_id,created_at,id);
CREATE INDEX data_route_device ON edgeai.data_route(source_device_id,id) WHERE source_device_id IS NOT NULL;

CREATE FUNCTION edgeai.protect_data_route() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Stream route definitions and history are immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.source_task_id IS NOT NULL AND NOT EXISTS(
        SELECT 1 FROM edgeai.task src JOIN edgeai.task dst ON dst.run_id=src.run_id
        JOIN edgeai.task_definition def ON def.id=src.definition_id
        JOIN edgeai.task_dependency dep ON dep.workflow_version_id=src.workflow_version_id
            AND dep.from_task_id=src.definition_id AND dep.to_task_id=dst.definition_id
        WHERE src.id=NEW.source_task_id AND dst.id=NEW.consumer_task_id AND src.run_id=NEW.run_id
          AND def.service_profile_version_id=NEW.source_profile_version_id AND dep.mode='STREAM'
          AND dep.from_port=NEW.source_port AND dep.to_port=NEW.consumer_port) THEN
        RAISE EXCEPTION 'Task stream route must match its published STREAM edge' USING ERRCODE='23514';
    END IF;
    IF NEW.source_device_id IS NOT NULL AND (NOT EXISTS(
        SELECT 1 FROM edgeai.device d WHERE d.id=NEW.source_device_id AND d.profile_version_id=NEW.source_profile_version_id
          AND d.source_mode=NEW.source_mode AND d.state='ACTIVE') OR EXISTS(
        SELECT 1 FROM edgeai.task t JOIN edgeai.task_dependency dep ON dep.to_task_id=t.definition_id
          AND dep.workflow_version_id=t.workflow_version_id WHERE t.id=NEW.consumer_task_id AND dep.to_port=NEW.consumer_port)) THEN
        RAISE EXCEPTION 'Device route requires current source and an unbound input' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER data_route_protected BEFORE INSERT OR UPDATE OR DELETE ON edgeai.data_route
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_data_route();
CREATE TRIGGER data_route_no_truncate BEFORE TRUNCATE ON edgeai.data_route
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_data_route();

CREATE TABLE edgeai.route_generation (
    id uuid PRIMARY KEY, route_id uuid NOT NULL, run_id uuid NOT NULL,
    generation bigint NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),
    source_task_id uuid, source_device_id uuid, consumer_task_id uuid NOT NULL,
    producer_attempt_id uuid, producer_session_id uuid, producer_epoch bigint NOT NULL CHECK(producer_epoch BETWEEN 1 AND 9007199254740991),
    consumer_attempt_id uuid NOT NULL, consumer_epoch bigint NOT NULL CHECK(consumer_epoch BETWEEN 1 AND 9007199254740991),
    broker_digest varchar(71) NOT NULL, policy_digest varchar(71) NOT NULL, request_digest varchar(71) NOT NULL,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, lease_until timestamptz NOT NULL,
    activated_at timestamptz, fenced_at timestamptz, fence_reason varchar(32), closed_at timestamptz,
    state varchar(16) GENERATED ALWAYS AS (CASE WHEN closed_at IS NOT NULL THEN 'CLOSED'
        WHEN fenced_at IS NOT NULL THEN 'FENCED' WHEN activated_at IS NOT NULL THEN 'ACTIVE' ELSE 'PREPARING' END) STORED,
    CONSTRAINT stream_generation_source CHECK(
        (source_task_id IS NOT NULL AND source_device_id IS NULL AND producer_attempt_id IS NOT NULL AND producer_session_id IS NULL) OR
        (source_task_id IS NULL AND source_device_id IS NOT NULL AND producer_attempt_id IS NULL AND producer_session_id IS NOT NULL)),
    CONSTRAINT stream_generation_digests CHECK(broker_digest ~ '^sha256:[a-f0-9]{64}$' AND policy_digest ~ '^sha256:[a-f0-9]{64}$' AND request_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT stream_generation_times CHECK(updated_at>=created_at AND lease_until>created_at
        AND (activated_at IS NULL OR activated_at BETWEEN created_at AND updated_at)
        AND (fenced_at IS NULL OR fenced_at BETWEEN coalesce(activated_at,created_at) AND updated_at)
        AND (closed_at IS NULL OR (fenced_at IS NOT NULL AND closed_at BETWEEN fenced_at AND updated_at))),
    CONSTRAINT stream_generation_fence CHECK((fenced_at IS NULL AND fence_reason IS NULL) OR (fenced_at IS NOT NULL AND fence_reason IS NOT NULL
        AND fence_reason IN ('CANCELLED','PRODUCER_CHANGED','CONSUMER_CHANGED','LEASE_EXPIRED','REPLACED','BROKER_CHANGED','FAILED','COMPLETED'))),
    FOREIGN KEY(route_id,run_id,consumer_task_id) REFERENCES edgeai.data_route(id,run_id,consumer_task_id),
    FOREIGN KEY(producer_attempt_id,source_task_id,producer_epoch) REFERENCES edgeai.task_attempt(id,task_id,epoch),
    FOREIGN KEY(producer_session_id,source_device_id,producer_epoch) REFERENCES edgeai.device_session(id,device_id,epoch),
    FOREIGN KEY(consumer_attempt_id,consumer_task_id,consumer_epoch) REFERENCES edgeai.task_attempt(id,task_id,epoch),
    UNIQUE(route_id,generation)
);
CREATE UNIQUE INDEX one_unrevoked_route_generation ON edgeai.route_generation(route_id) WHERE closed_at IS NULL;
CREATE INDEX route_generation_history ON edgeai.route_generation(route_id,generation DESC);
CREATE INDEX route_generation_expiry ON edgeai.route_generation(lease_until,id) WHERE fenced_at IS NULL;

CREATE FUNCTION edgeai.protect_route_generation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE route edgeai.data_route; previous bigint; check_actors boolean;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'Stream generation history must be retained' USING ERRCODE='23514';
    END IF;
    SELECT * INTO route FROM edgeai.data_route WHERE id=NEW.route_id FOR UPDATE;
    IF route.id IS NULL OR (NEW.source_task_id,NEW.source_device_id) IS DISTINCT FROM (route.source_task_id,route.source_device_id) THEN
        RAISE EXCEPTION 'Stream generation source does not match route' USING ERRCODE='23514';
    END IF;
    check_actors=false;
    IF TG_OP='INSERT' THEN
        SELECT coalesce(max(generation),0) INTO previous FROM edgeai.route_generation WHERE route_id=NEW.route_id;
        IF NEW.generation<>previous+1 OR NEW.activated_at IS NOT NULL OR NEW.fenced_at IS NOT NULL OR NEW.closed_at IS NOT NULL
            OR NEW.created_at<>NEW.updated_at OR NEW.lease_until<NEW.created_at+interval '5 seconds'
            OR NEW.lease_until>NEW.created_at+interval '120 seconds' THEN
            RAISE EXCEPTION 'Stream generation must begin with the next pending lease' USING ERRCODE='23514';
        END IF;
        check_actors=true;
    ELSE
        IF (to_jsonb(OLD)-ARRAY['lease_until','activated_at','fenced_at','fence_reason','closed_at','updated_at','state']) IS DISTINCT FROM
           (to_jsonb(NEW)-ARRAY['lease_until','activated_at','fenced_at','fence_reason','closed_at','updated_at','state'])
           OR (OLD.closed_at IS NOT NULL AND (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state'))
           OR NEW.updated_at<OLD.updated_at
           OR (OLD.fenced_at IS NOT NULL AND (NEW.fenced_at,NEW.fence_reason,NEW.lease_until,NEW.activated_at)
               IS DISTINCT FROM (OLD.fenced_at,OLD.fence_reason,OLD.lease_until,OLD.activated_at))
           OR (OLD.activated_at IS NOT NULL AND NEW.activated_at IS DISTINCT FROM OLD.activated_at)
           OR (NEW.closed_at IS NOT NULL AND OLD.fenced_at IS NULL) THEN
            RAISE EXCEPTION 'Stream generation identity and terminal history are immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.activated_at IS DISTINCT FROM OLD.activated_at OR NEW.lease_until IS DISTINCT FROM OLD.lease_until THEN
            IF OLD.fenced_at IS NOT NULL OR OLD.lease_until<=NEW.updated_at OR NEW.lease_until<OLD.lease_until
                OR NEW.lease_until>NEW.updated_at+interval '120 seconds'
                OR (NEW.lease_until<>OLD.lease_until AND OLD.activated_at IS NULL) THEN
                RAISE EXCEPTION 'Stream activation and renewal require a live lease' USING ERRCODE='23514';
            END IF;
            check_actors=true;
        END IF;
    END IF;
    IF check_actors THEN
        IF NOT EXISTS(SELECT 1 FROM edgeai.workflow_run w JOIN edgeai.task t ON t.run_id=w.id
            JOIN edgeai.task_attempt a ON a.task_id=t.id WHERE w.id=NEW.run_id AND w.state IN ('PENDING','RUNNING')
              AND t.id=NEW.consumer_task_id AND t.state IN ('READY','RUNNING') AND a.id=NEW.consumer_attempt_id
              AND a.epoch=NEW.consumer_epoch AND a.state IN ('QUEUED','DISPATCHING','RUNNING')) THEN
            RAISE EXCEPTION 'Stream consumer must be a current active Attempt' USING ERRCODE='23514';
        END IF;
        IF NEW.source_task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM edgeai.task t JOIN edgeai.task_attempt a ON a.task_id=t.id
            WHERE t.id=NEW.source_task_id AND t.run_id=NEW.run_id AND t.state IN ('READY','RUNNING')
              AND a.id=NEW.producer_attempt_id AND a.epoch=NEW.producer_epoch AND a.state IN ('QUEUED','DISPATCHING','RUNNING')) THEN
            RAISE EXCEPTION 'Stream producer must be a current active Attempt' USING ERRCODE='23514';
        END IF;
        IF NEW.source_device_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM edgeai.device d JOIN edgeai.device_session s ON s.device_id=d.id
            WHERE d.id=NEW.source_device_id AND d.state='ACTIVE' AND s.closed_at IS NULL AND s.id=NEW.producer_session_id
              AND s.epoch=NEW.producer_epoch AND d.session_epoch=s.epoch) THEN
            RAISE EXCEPTION 'Stream producer must be the current Device session' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER route_generation_protected BEFORE INSERT OR UPDATE OR DELETE ON edgeai.route_generation
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_route_generation();
CREATE TRIGGER route_generation_no_truncate BEFORE TRUNCATE ON edgeai.route_generation
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_route_generation();
