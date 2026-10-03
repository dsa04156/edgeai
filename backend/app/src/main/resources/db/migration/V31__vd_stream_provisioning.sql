-- VD child Runners use the same immutable STREAM membership and Device session pins.
CREATE OR REPLACE FUNCTION edgeai.protect_stream_provisioning() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r edgeai.data_route; w edgeai.workflow_run;
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Stream provisioning history is immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO w FROM edgeai.workflow_run WHERE id=NEW.run_id FOR UPDATE;
    IF TG_TABLE_NAME='stream_run_configuration' AND (
        w.id IS NULL OR w.state<>'PENDING' OR w.mode NOT IN ('AUTO','NODE','VD')
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
