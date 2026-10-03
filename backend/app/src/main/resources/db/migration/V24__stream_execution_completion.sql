-- Route membership is immutable before any Runner receives an execution assignment.
CREATE TABLE edgeai.stream_run_binding (
    run_id uuid PRIMARY KEY REFERENCES edgeai.workflow_run(id),
    route_digest varchar(71) NOT NULL CHECK(route_digest ~ '^sha256:[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL
);
ALTER TABLE edgeai.stream_checkpoint ADD CONSTRAINT stream_checkpoint_attempt_identity UNIQUE(id,attempt_id);
CREATE TABLE edgeai.stream_task_completion (
    attempt_id uuid PRIMARY KEY REFERENCES edgeai.task_attempt(id),
    checkpoint_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL, granted_at timestamptz,
    FOREIGN KEY(checkpoint_id,attempt_id) REFERENCES edgeai.stream_checkpoint(id,attempt_id),
    CONSTRAINT stream_task_grant_time CHECK(granted_at IS NULL OR granted_at>=created_at)
);
CREATE TABLE edgeai.stream_device_completion (
    generation_id uuid PRIMARY KEY REFERENCES edgeai.route_generation(id),
    sequence bigint NOT NULL CHECK(sequence BETWEEN 1 AND 9007199254740991),
    created_at timestamptz NOT NULL, granted_at timestamptz,
    CONSTRAINT stream_device_grant_time CHECK(granted_at IS NULL OR granted_at>=created_at)
);

CREATE FUNCTION edgeai.protect_stream_run_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Stream route membership is immutable' USING ERRCODE='23514'; END IF;
    PERFORM 1 FROM edgeai.workflow_run WHERE id=NEW.run_id FOR UPDATE;
    RETURN NEW;
END; $$;
CREATE TRIGGER stream_run_binding_immutable BEFORE INSERT OR UPDATE OR DELETE ON edgeai.stream_run_binding
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_stream_run_binding();
CREATE TRIGGER stream_run_binding_no_truncate BEFORE TRUNCATE ON edgeai.stream_run_binding
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_stream_run_binding();

CREATE FUNCTION edgeai.require_unfrozen_stream_routes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM edgeai.workflow_run WHERE id=NEW.run_id FOR UPDATE;
    IF EXISTS(SELECT 1 FROM edgeai.stream_run_binding WHERE run_id=NEW.run_id) THEN
        RAISE EXCEPTION 'Cannot extend frozen stream route membership' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER stream_route_frozen_membership BEFORE INSERT ON edgeai.data_route
    FOR EACH ROW EXECUTE FUNCTION edgeai.require_unfrozen_stream_routes();

CREATE FUNCTION edgeai.protect_stream_completion() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c edgeai.stream_checkpoint; g edgeai.route_generation; rid uuid;
BEGIN
    IF TG_OP NOT IN ('INSERT','UPDATE') THEN
        RAISE EXCEPTION 'Stream completion history is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' THEN
        IF to_jsonb(NEW)-'granted_at' IS DISTINCT FROM to_jsonb(OLD)-'granted_at'
            OR (OLD.granted_at IS NOT NULL AND NEW.granted_at IS DISTINCT FROM OLD.granted_at) OR NEW.granted_at IS NULL THEN
            RAISE EXCEPTION 'Only the first completion grant may be recorded' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.granted_at IS NOT NULL THEN RAISE EXCEPTION 'Completion must first be reported' USING ERRCODE='23514'; END IF;
    IF TG_TABLE_NAME='stream_task_completion' THEN
        SELECT * INTO c FROM edgeai.stream_checkpoint WHERE id=NEW.checkpoint_id;
        rid=c.run_id;
        PERFORM 1 FROM edgeai.workflow_run WHERE id=rid FOR UPDATE;
        IF c.id IS NULL OR c.attempt_id<>NEW.attempt_id OR c.id IS DISTINCT FROM
            (SELECT id FROM edgeai.stream_checkpoint WHERE task_id=c.task_id ORDER BY serial DESC LIMIT 1)
            OR EXISTS(SELECT 1 FROM jsonb_array_elements(c.summary_json->'routes') r
                WHERE r->>'ended' IS DISTINCT FROM 'true' OR (r->>'received')::bigint<1 OR r->>'received' IS DISTINCT FROM r->>'committed') THEN
            RAISE EXCEPTION 'Completion requires the latest terminal checkpoint' USING ERRCODE='23514';
        END IF;
    ELSE
        SELECT * INTO g FROM edgeai.route_generation WHERE id=NEW.generation_id;
        rid=g.run_id;
        PERFORM 1 FROM edgeai.workflow_run WHERE id=rid FOR UPDATE;
        IF g.id IS NULL OR g.source_device_id IS NULL THEN
            RAISE EXCEPTION 'Device completion requires a Device route' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NOT EXISTS(SELECT 1 FROM edgeai.stream_run_binding WHERE run_id=rid) THEN
        RAISE EXCEPTION 'Completion requires frozen route membership' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER stream_task_completion_immutable BEFORE INSERT OR UPDATE OR DELETE ON edgeai.stream_task_completion
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_stream_completion();
CREATE TRIGGER stream_device_completion_immutable BEFORE INSERT OR UPDATE OR DELETE ON edgeai.stream_device_completion
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_stream_completion();
CREATE TRIGGER stream_task_completion_no_truncate BEFORE TRUNCATE ON edgeai.stream_task_completion
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_stream_completion();
CREATE TRIGGER stream_device_completion_no_truncate BEFORE TRUNCATE ON edgeai.stream_device_completion
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_stream_completion();

CREATE FUNCTION edgeai.require_unreported_stream_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM edgeai.workflow_run WHERE id=NEW.run_id FOR UPDATE;
    IF EXISTS(SELECT 1 FROM edgeai.stream_task_completion WHERE attempt_id=NEW.attempt_id) THEN
        RAISE EXCEPTION 'A reported terminal checkpoint cannot advance' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER stream_checkpoint_terminal BEFORE INSERT ON edgeai.stream_checkpoint
    FOR EACH ROW EXECUTE FUNCTION edgeai.require_unreported_stream_checkpoint();
