-- Freeze provider selection before any remote request, including deferred children and transfers.
CREATE FUNCTION edgeai.valid_remote_target(key text,digest text,source text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$ SELECT key IS NOT NULL AND digest IS NOT NULL AND source IS NOT NULL
    AND key ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$' AND length(key)<=63
    AND digest ~ '^sha256:[a-f0-9]{64}$' AND source IN ('SYNTHETIC','EXTERNAL') $$;
ALTER TABLE edgeai.workflow_run ADD COLUMN remote_provider_key varchar(63), ADD COLUMN remote_configuration_digest varchar(71), ADD COLUMN remote_source_mode varchar(9);
ALTER TABLE edgeai.task_attempt ADD COLUMN remote_provider_key varchar(63), ADD COLUMN remote_configuration_digest varchar(71), ADD COLUMN remote_source_mode varchar(9);
ALTER TABLE edgeai.task_offload ADD COLUMN remote_provider_key varchar(63), ADD COLUMN remote_configuration_digest varchar(71), ADD COLUMN remote_source_mode varchar(9);

-- Earlier internal integration fixtures already have allocations. Preserve their actual binding.
UPDATE edgeai.workflow_run w SET remote_provider_key=b.provider_key,remote_configuration_digest=b.configuration_digest,remote_source_mode=b.source_mode
FROM (SELECT DISTINCT ON (r.run_id) r.run_id,a.provider_key,a.configuration_digest,a.source_mode
      FROM edgeai.runtime_instance r JOIN edgeai.remote_allocation a ON a.id=r.remote_allocation_id ORDER BY r.run_id,r.created_at,r.id) b
WHERE w.id=b.run_id AND w.mode='REMOTE';
UPDATE edgeai.task_attempt a SET remote_provider_key=w.remote_provider_key,remote_configuration_digest=w.remote_configuration_digest,remote_source_mode=w.remote_source_mode
FROM edgeai.task t JOIN edgeai.workflow_run w ON w.id=t.run_id WHERE a.task_id=t.id AND a.mode='REMOTE';
UPDATE edgeai.task_attempt a SET remote_provider_key=b.provider_key,remote_configuration_digest=b.configuration_digest,remote_source_mode=b.source_mode
FROM edgeai.runtime_instance r JOIN edgeai.remote_allocation b ON b.id=r.remote_allocation_id WHERE r.attempt_id=a.id;

ALTER TABLE edgeai.workflow_run ADD CONSTRAINT run_remote_binding CHECK(
    (mode='REMOTE' AND edgeai.valid_remote_target(remote_provider_key,remote_configuration_digest,remote_source_mode)) OR
    (mode<>'REMOTE' AND remote_provider_key IS NULL AND remote_configuration_digest IS NULL AND remote_source_mode IS NULL));
ALTER TABLE edgeai.task_attempt ADD CONSTRAINT attempt_remote_binding CHECK(
    (mode='REMOTE' AND edgeai.valid_remote_target(remote_provider_key,remote_configuration_digest,remote_source_mode)) OR
    (mode<>'REMOTE' AND remote_provider_key IS NULL AND remote_configuration_digest IS NULL AND remote_source_mode IS NULL));
ALTER TABLE edgeai.task_offload DROP CONSTRAINT offload_decision;
ALTER TABLE edgeai.task_offload ADD CONSTRAINT offload_decision CHECK(
    (trigger='MANUAL' AND cardinality(excluded_node_names)=0 AND decision IS NULL AND
        ((target_node_id IS NOT NULL AND remote_provider_key IS NULL AND remote_configuration_digest IS NULL AND remote_source_mode IS NULL) OR
         (target_node_id IS NULL AND edgeai.valid_remote_target(remote_provider_key,remote_configuration_digest,remote_source_mode)))) OR
    (trigger IN ('CPU','MEMORY','LATENCY') AND target_node_id IS NULL AND remote_provider_key IS NULL AND remote_configuration_digest IS NULL AND remote_source_mode IS NULL
     AND cardinality(excluded_node_names) BETWEEN 1 AND 16 AND decision IS NOT NULL AND jsonb_typeof(decision)='object'));

CREATE FUNCTION edgeai.protect_execution_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW)->'mode') IS DISTINCT FROM (to_jsonb(OLD)->'mode') OR
       (to_jsonb(NEW)->'node_id') IS DISTINCT FROM (to_jsonb(OLD)->'node_id') OR
       (to_jsonb(NEW)->'target_node_id') IS DISTINCT FROM (to_jsonb(OLD)->'target_node_id') OR
       NEW.remote_provider_key IS DISTINCT FROM OLD.remote_provider_key OR
       NEW.remote_configuration_digest IS DISTINCT FROM OLD.remote_configuration_digest OR
       NEW.remote_source_mode IS DISTINCT FROM OLD.remote_source_mode THEN
        RAISE EXCEPTION 'Execution target is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER run_target_immutable BEFORE UPDATE ON edgeai.workflow_run FOR EACH ROW EXECUTE FUNCTION edgeai.protect_execution_target();
CREATE TRIGGER attempt_target_immutable BEFORE UPDATE ON edgeai.task_attempt FOR EACH ROW EXECUTE FUNCTION edgeai.protect_execution_target();
CREATE TRIGGER offload_target_immutable BEFORE UPDATE ON edgeai.task_offload FOR EACH ROW EXECUTE FUNCTION edgeai.protect_execution_target();

CREATE FUNCTION edgeai.check_remote_attempt_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM edgeai.runtime_instance r JOIN edgeai.task_attempt a ON a.id=r.attempt_id
        WHERE r.id=NEW.runtime_id AND a.mode='REMOTE' AND a.remote_provider_key=NEW.provider_key
          AND a.remote_configuration_digest=NEW.configuration_digest AND a.remote_source_mode=NEW.source_mode) THEN
        RAISE EXCEPTION 'Allocation must use the pinned Attempt provider' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER remote_attempt_binding BEFORE INSERT ON edgeai.remote_allocation FOR EACH ROW EXECUTE FUNCTION edgeai.check_remote_attempt_binding();
