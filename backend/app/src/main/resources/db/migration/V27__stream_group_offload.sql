ALTER TABLE edgeai.task_offload ADD CONSTRAINT offload_run_identity UNIQUE(id,run_id);
CREATE TABLE edgeai.task_offload_member (
    operation_id uuid NOT NULL,run_id uuid NOT NULL,task_id uuid NOT NULL,
    source_attempt_id uuid NOT NULL UNIQUE,target_attempt_id uuid UNIQUE,
    checkpoint_id uuid NOT NULL REFERENCES edgeai.stream_checkpoint(id),
    target_node_id uuid REFERENCES edgeai.execution_node(id),
    excluded_node_names text[] NOT NULL DEFAULT '{}',
    PRIMARY KEY(operation_id,task_id),
    FOREIGN KEY(operation_id,run_id) REFERENCES edgeai.task_offload(id,run_id),
    FOREIGN KEY(task_id,run_id) REFERENCES edgeai.task(id,run_id),
    FOREIGN KEY(source_attempt_id,task_id) REFERENCES edgeai.task_attempt(id,task_id),
    FOREIGN KEY(target_attempt_id,task_id) REFERENCES edgeai.task_attempt(id,task_id),
    CHECK(target_attempt_id IS NULL OR target_attempt_id<>source_attempt_id),
    CHECK(cardinality(excluded_node_names)<=16 AND (target_node_id IS NULL OR cardinality(excluded_node_names)=0))
);
CREATE INDEX offload_member_task ON edgeai.task_offload_member(task_id,operation_id);
CREATE FUNCTION edgeai.protect_stream_offload_member() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o edgeai.task_offload; a edgeai.task_attempt; p edgeai.task_attempt; cp edgeai.stream_checkpoint;
BEGIN
    IF TG_OP NOT IN ('INSERT','UPDATE') THEN
        RAISE EXCEPTION 'Stream transfer history is immutable' USING ERRCODE='23514';
    END IF;
    PERFORM 1 FROM edgeai.workflow_run WHERE id=NEW.run_id FOR UPDATE;
    SELECT * INTO o FROM edgeai.task_offload WHERE id=NEW.operation_id;
    SELECT * INTO p FROM edgeai.task_attempt WHERE id=NEW.source_attempt_id;
    SELECT * INTO cp FROM edgeai.stream_checkpoint WHERE id=NEW.checkpoint_id;
    IF o.id IS NULL OR o.state<>'DRAINING' OR o.remote_provider_key IS NOT NULL OR o.trigger<>'MANUAL'
        OR p.id IS NULL OR p.mode NOT IN ('AUTO','NODE') OR cp.attempt_id IS DISTINCT FROM p.id
        OR cp.task_id IS DISTINCT FROM NEW.task_id
        OR EXISTS(SELECT 1 FROM edgeai.stream_task_completion WHERE attempt_id=p.id AND granted_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Stream transfer requires an unsealed checkpoint and a local source' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.target_attempt_id IS NOT NULL OR p.state<>'RUNNING'
            OR (SELECT state FROM edgeai.task WHERE id=NEW.task_id)<>'RUNNING'
            OR cp.id IS DISTINCT FROM (SELECT id FROM edgeai.stream_checkpoint WHERE task_id=NEW.task_id ORDER BY serial DESC LIMIT 1) THEN
            RAISE EXCEPTION 'Stream transfer must pin the current checkpoint before fencing' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (to_jsonb(NEW)-'target_attempt_id') IS DISTINCT FROM (to_jsonb(OLD)-'target_attempt_id')
            OR OLD.target_attempt_id IS NOT NULL OR NEW.target_attempt_id IS NULL THEN
            RAISE EXCEPTION 'Stream transfer plan and target are immutable' USING ERRCODE='23514';
        END IF;
        SELECT * INTO a FROM edgeai.task_attempt WHERE id=NEW.target_attempt_id;
        IF a.id IS NULL OR a.task_id<>p.task_id OR a.cause<>'OFFLOAD' OR a.state<>'QUEUED'
            OR a.epoch<>p.epoch+1 OR a.number<>p.number+1 OR p.state<>'OFFLOADED'
            OR a.mode<>(CASE WHEN NEW.target_node_id IS NULL THEN 'AUTO' ELSE 'NODE' END)
            OR a.node_id IS DISTINCT FROM NEW.target_node_id OR a.excluded_node_names<>NEW.excluded_node_names
            OR NOT EXISTS(SELECT 1 FROM edgeai.runtime_instance WHERE attempt_id=p.id)
            OR EXISTS(SELECT 1 FROM edgeai.runtime_instance r WHERE r.task_id=NEW.task_id AND
                (r.desired_state<>'STOPPED' OR r.observed_state<>'TERMINATED'
                 OR EXISTS(SELECT 1 FROM edgeai.runtime_command c WHERE c.runtime_id=r.id AND c.kind='CREATE' AND NOT c.completed)))
            OR EXISTS(SELECT 1 FROM edgeai.route_generation WHERE closed_at IS NULL
                AND (source_task_id=NEW.task_id OR consumer_task_id=NEW.task_id)) THEN
            RAISE EXCEPTION 'Stream successor requires a stopped source and revoked routes' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER stream_offload_member_guard BEFORE INSERT OR UPDATE OR DELETE ON edgeai.task_offload_member
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_stream_offload_member();
CREATE TRIGGER stream_offload_member_no_truncate BEFORE TRUNCATE ON edgeai.task_offload_member
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_stream_offload_member();
