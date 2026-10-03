-- Finalizer retries read the original sealed state; they never mint a new computation grant.
CREATE TABLE edgeai.stream_finalization_recovery (
    attempt_id uuid PRIMARY KEY REFERENCES edgeai.task_attempt(id),
    predecessor_attempt_id uuid NOT NULL UNIQUE REFERENCES edgeai.task_attempt(id),
    granted_attempt_id uuid NOT NULL REFERENCES edgeai.stream_task_completion(attempt_id),
    created_at timestamptz NOT NULL,
    CHECK(attempt_id<>predecessor_attempt_id AND attempt_id<>granted_attempt_id)
);
CREATE FUNCTION edgeai.protect_stream_finalization_recovery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a edgeai.task_attempt; p edgeai.task_attempt; t edgeai.task;
    c edgeai.stream_task_completion; cp edgeai.stream_checkpoint;
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Finalizer recovery history is immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO a FROM edgeai.task_attempt WHERE id=NEW.attempt_id;
    SELECT * INTO t FROM edgeai.task WHERE id=a.task_id;
    PERFORM 1 FROM edgeai.workflow_run WHERE id=t.run_id FOR UPDATE;
    SELECT * INTO a FROM edgeai.task_attempt WHERE id=NEW.attempt_id;
    SELECT * INTO t FROM edgeai.task WHERE id=a.task_id;
    SELECT * INTO p FROM edgeai.task_attempt WHERE id=NEW.predecessor_attempt_id;
    SELECT * INTO c FROM edgeai.stream_task_completion WHERE attempt_id=NEW.granted_attempt_id;
    SELECT * INTO cp FROM edgeai.stream_checkpoint WHERE id=c.checkpoint_id;
    IF a.id IS NULL OR p.id IS NULL OR c.granted_at IS NULL OR cp.id IS NULL
        OR a.task_id<>p.task_id OR a.task_id<>cp.task_id OR a.epoch<>p.epoch+1 OR a.number<>p.number+1
        OR a.state<>'QUEUED' OR a.cause<>'RETRY' OR a.mode NOT IN ('AUTO','NODE') OR p.state<>'FAILED'
        OR t.state<>'READY' OR (SELECT state FROM edgeai.workflow_run WHERE id=t.run_id)<>'RUNNING'
        OR NEW.created_at<c.granted_at
        OR cp.id IS DISTINCT FROM (SELECT id FROM edgeai.stream_checkpoint WHERE task_id=t.id ORDER BY serial DESC LIMIT 1)
        OR NOT (p.id=c.attempt_id OR EXISTS(SELECT 1 FROM edgeai.stream_finalization_recovery
            WHERE attempt_id=p.id AND granted_attempt_id=c.attempt_id))
        OR NOT EXISTS(SELECT 1 FROM edgeai.runtime_instance WHERE attempt_id=p.id)
        OR EXISTS(SELECT 1 FROM edgeai.task_result WHERE task_id=t.id)
        OR EXISTS(SELECT 1 FROM edgeai.runtime_instance r WHERE r.task_id=t.id AND
            (r.desired_state<>'STOPPED' OR r.observed_state<>'TERMINATED'
             OR EXISTS(SELECT 1 FROM edgeai.runtime_command rc WHERE rc.runtime_id=r.id AND rc.kind='CREATE' AND NOT rc.completed)))
        OR EXISTS(SELECT 1 FROM edgeai.route_generation g WHERE g.closed_at IS NULL
            AND (g.source_task_id=t.id OR g.consumer_task_id=t.id)) THEN
        RAISE EXCEPTION 'Finalizer successor requires the sealed grant and stopped predecessor' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER stream_finalization_recovery_immutable BEFORE INSERT OR UPDATE OR DELETE ON edgeai.stream_finalization_recovery
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_stream_finalization_recovery();
CREATE TRIGGER stream_finalization_recovery_no_truncate BEFORE TRUNCATE ON edgeai.stream_finalization_recovery
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_stream_finalization_recovery();

CREATE OR REPLACE FUNCTION edgeai.require_unreported_stream_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM edgeai.workflow_run WHERE id=NEW.run_id FOR UPDATE;
    IF EXISTS(SELECT 1 FROM edgeai.stream_task_completion WHERE attempt_id=NEW.attempt_id)
        OR EXISTS(SELECT 1 FROM edgeai.stream_task_completion c JOIN edgeai.task_attempt a ON a.id=c.attempt_id
            WHERE a.task_id=NEW.task_id AND c.granted_at IS NOT NULL) THEN
        RAISE EXCEPTION 'A sealed computation cannot advance its checkpoint' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
