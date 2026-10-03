-- Public STREAM requests pin their broker and Device sessions before any dispatch.
CREATE TABLE edgeai.stream_run_configuration (
    run_id uuid PRIMARY KEY REFERENCES edgeai.workflow_run(id),
    namespace varchar(63) NOT NULL CHECK(namespace ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'),
    broker_digest varchar(71) NOT NULL CHECK(broker_digest ~ '^sha256:[a-f0-9]{64}$'),
    lease_seconds integer NOT NULL CHECK(lease_seconds BETWEEN 5 AND 120),
    created_at timestamptz NOT NULL
);
CREATE TABLE edgeai.stream_device_binding (
    route_id uuid PRIMARY KEY REFERENCES edgeai.data_route(id),
    run_id uuid NOT NULL REFERENCES edgeai.stream_run_configuration(run_id),
    device_id uuid NOT NULL,
    session_id uuid NOT NULL,
    epoch bigint NOT NULL CHECK(epoch BETWEEN 1 AND 9007199254740991),
    FOREIGN KEY(session_id,device_id,epoch) REFERENCES edgeai.device_session(id,device_id,epoch)
);
CREATE INDEX stream_run_namespace_scan ON edgeai.stream_run_configuration(namespace,run_id);
CREATE INDEX stream_device_binding_run ON edgeai.stream_device_binding(run_id,route_id);

CREATE FUNCTION edgeai.protect_stream_provisioning() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r edgeai.data_route; w edgeai.workflow_run;
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Stream provisioning history is immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO w FROM edgeai.workflow_run WHERE id=NEW.run_id FOR UPDATE;
    IF TG_TABLE_NAME='stream_run_configuration' AND (
        w.id IS NULL OR w.state<>'PENDING' OR w.mode NOT IN ('AUTO','NODE')
        OR EXISTS(SELECT 1 FROM edgeai.task_attempt a JOIN edgeai.task t ON t.id=a.task_id WHERE t.run_id=NEW.run_id)
        OR EXISTS(SELECT 1 FROM edgeai.stream_run_binding WHERE run_id=NEW.run_id)) THEN
        RAISE EXCEPTION 'Stream provisioning must precede all attempts and frozen membership' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='stream_device_binding' THEN
        SELECT * INTO r FROM edgeai.data_route WHERE id=NEW.route_id;
        IF r.id IS NULL OR r.run_id<>NEW.run_id OR r.source_device_id IS DISTINCT FROM NEW.device_id
            OR EXISTS(SELECT 1 FROM edgeai.stream_run_binding WHERE run_id=NEW.run_id) THEN
            RAISE EXCEPTION 'Device binding must match an unfrozen Run route' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER stream_run_configuration_immutable BEFORE INSERT OR UPDATE OR DELETE ON edgeai.stream_run_configuration
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_stream_provisioning();
CREATE TRIGGER stream_run_configuration_no_truncate BEFORE TRUNCATE ON edgeai.stream_run_configuration
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_stream_provisioning();
CREATE TRIGGER stream_device_binding_immutable BEFORE INSERT OR UPDATE OR DELETE ON edgeai.stream_device_binding
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_stream_provisioning();
CREATE TRIGGER stream_device_binding_no_truncate BEFORE TRUNCATE ON edgeai.stream_device_binding
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_stream_provisioning();
