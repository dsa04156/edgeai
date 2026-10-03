-- Preserve the Run default and pin each Task's effective INITIAL placement.
ALTER TABLE edgeai.workflow_run ADD COLUMN task_executions jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK(jsonb_typeof(task_executions)='object');
ALTER TABLE edgeai.task ADD COLUMN initial_mode varchar(8),
    ADD COLUMN initial_node_id uuid REFERENCES edgeai.execution_node(id);
UPDATE edgeai.task t SET initial_mode=w.mode,initial_node_id=w.node_id FROM edgeai.workflow_run w WHERE w.id=t.run_id;
ALTER TABLE edgeai.task ALTER COLUMN initial_mode SET NOT NULL,
    ADD CONSTRAINT task_initial_target CHECK(
        (initial_mode IN ('AUTO','REMOTE','VD') AND initial_node_id IS NULL) OR
        (initial_mode='NODE' AND initial_node_id IS NOT NULL));

CREATE FUNCTION edgeai.protect_run_task_executions() RETURNS trigger LANGUAGE plpgsql AS $$
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
    IF (SELECT count(*) FROM jsonb_object_keys(NEW.task_executions))>128 OR
       (NEW.task_executions<>'{}'::jsonb AND NEW.mode NOT IN ('AUTO','NODE')) THEN
        RAISE EXCEPTION 'Task overrides require a bounded Kubernetes execution plan' USING ERRCODE='23514';
    END IF;
    FOR item IN SELECT * FROM jsonb_each(NEW.task_executions) LOOP
        IF NOT EXISTS(SELECT 1 FROM edgeai.task_definition WHERE workflow_version_id=NEW.workflow_version_id AND task_key=item.key)
           OR jsonb_typeof(item.value)<>'object' THEN
            RAISE EXCEPTION 'Task override must reference a published Task and placement' USING ERRCODE='23514';
        END IF;
        placement_mode=item.value->>'mode';
        SELECT count(*) INTO fields FROM jsonb_object_keys(item.value);
        IF placement_mode IS NULL OR placement_mode NOT IN ('AUTO','NODE') OR
           (placement_mode='AUTO' AND fields<>1) OR (placement_mode='NODE' AND fields<>2) THEN
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
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER run_task_executions_protected BEFORE INSERT OR UPDATE ON edgeai.workflow_run
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_run_task_executions();

CREATE FUNCTION edgeai.pin_task_initial_execution() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_mode text; expected_node uuid; override jsonb;
BEGIN
    IF TG_OP='UPDATE' THEN
        IF (NEW.run_id,NEW.workflow_version_id,NEW.definition_id,NEW.initial_mode,NEW.initial_node_id) IS DISTINCT FROM
           (OLD.run_id,OLD.workflow_version_id,OLD.definition_id,OLD.initial_mode,OLD.initial_node_id) THEN
            RAISE EXCEPTION 'Task identity and initial placement are immutable' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT w.mode,w.node_id,w.task_executions->d.task_key INTO expected_mode,expected_node,override
    FROM edgeai.workflow_run w JOIN edgeai.task_definition d ON d.workflow_version_id=w.workflow_version_id
    WHERE w.id=NEW.run_id AND w.workflow_version_id=NEW.workflow_version_id AND d.id=NEW.definition_id FOR UPDATE OF w;
    IF expected_mode IS NULL THEN
        RAISE EXCEPTION 'Task placement requires its own Run and definition' USING ERRCODE='23514';
    END IF;
    IF override IS NOT NULL THEN
        expected_mode=override->>'mode';expected_node=(override->>'nodeId')::uuid;
    END IF;
    IF (NEW.initial_mode IS NOT NULL AND NEW.initial_mode<>expected_mode) OR
       (NEW.initial_node_id IS NOT NULL AND NEW.initial_node_id IS DISTINCT FROM expected_node) THEN
        RAISE EXCEPTION 'Task placement must match its pinned Run plan' USING ERRCODE='23514';
    END IF;
    NEW.initial_mode=expected_mode;NEW.initial_node_id=expected_node;
    RETURN NEW;
END;
$$;
CREATE TRIGGER task_initial_execution_pinned BEFORE INSERT OR UPDATE ON edgeai.task
    FOR EACH ROW EXECUTE FUNCTION edgeai.pin_task_initial_execution();

CREATE FUNCTION edgeai.check_initial_attempt_placement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.cause='INITIAL' AND (NEW.number<>1 OR NEW.epoch<>1 OR NOT EXISTS(
        SELECT 1 FROM edgeai.task t WHERE t.id=NEW.task_id AND t.initial_mode=NEW.mode
            AND t.initial_node_id IS NOT DISTINCT FROM NEW.node_id)) THEN
        RAISE EXCEPTION 'INITIAL Attempt must use its Task placement' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER initial_attempt_placement BEFORE INSERT ON edgeai.task_attempt
    FOR EACH ROW EXECUTE FUNCTION edgeai.check_initial_attempt_placement();
