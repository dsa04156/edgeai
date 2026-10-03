-- Immutable externally verified snapshots. Raw frames/state remain in versioned S3.
CREATE TABLE edgeai.stream_checkpoint (
    id uuid PRIMARY KEY, run_id uuid NOT NULL, task_id uuid NOT NULL,
    attempt_id uuid NOT NULL, runtime_id uuid NOT NULL, epoch bigint NOT NULL,
    producer_pod_uid uuid NOT NULL, service_profile_version_id uuid NOT NULL REFERENCES edgeai.profile_version(id),
    previous_id uuid, serial bigint NOT NULL CHECK(serial BETWEEN 0 AND 9007199254740991),
    state_revision bigint NOT NULL CHECK(state_revision BETWEEN 0 AND serial),
    sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
    execution_sha256 varchar(64) NOT NULL CHECK(execution_sha256 ~ '^[a-f0-9]{64}$'),
    bytes bigint NOT NULL CHECK(bytes BETWEEN 1 AND 75497472),
    generation_ids uuid[] NOT NULL CHECK(cardinality(generation_ids) BETWEEN 1 AND 32),
    summary_json jsonb NOT NULL CHECK(jsonb_typeof(summary_json)='object' AND octet_length(summary_json::text)<=65536),
    bucket varchar(63) NOT NULL, object_key varchar(512) NOT NULL,
    object_version varchar(1024) NOT NULL CHECK(length(object_version)>0 AND object_version<>'null'),
    created_at timestamptz NOT NULL,
    UNIQUE(id,task_id), UNIQUE(attempt_id,serial), UNIQUE(task_id,serial),
    FOREIGN KEY(task_id,run_id) REFERENCES edgeai.task(id,run_id),
    FOREIGN KEY(runtime_id,attempt_id,task_id,epoch) REFERENCES edgeai.runtime_instance(id,attempt_id,task_id,epoch),
    FOREIGN KEY(previous_id,task_id) REFERENCES edgeai.stream_checkpoint(id,task_id),
    CHECK(summary_json ?& ARRAY['manifest','revision','routes','stateSha256','stateBytes']
        AND summary_json-ARRAY['manifest','revision','routes','stateSha256','stateBytes']='{}'::jsonb
        AND jsonb_typeof(summary_json->'manifest')='object' AND jsonb_typeof(summary_json->'routes')='array'
        AND summary_json->>'stateSha256' ~ '^[a-f0-9]{64}$' AND (summary_json->>'revision')::bigint=state_revision),
    CHECK(object_key='tasks/'||task_id::text||'/attempts/'||attempt_id::text||'/stream-checkpoint/'||sha256)
);
CREATE INDEX stream_checkpoint_latest ON edgeai.stream_checkpoint(task_id,serial DESC);
CREATE FUNCTION edgeai.protect_stream_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous edgeai.stream_checkpoint; r edgeai.runtime_instance; g edgeai.route_generation; gid uuid; cursor_row jsonb; prior jsonb; input boolean;
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Verified checkpoints are immutable' USING ERRCODE='23514'; END IF;
    PERFORM 1 FROM edgeai.workflow_run WHERE id=NEW.run_id FOR UPDATE;
    SELECT * INTO r FROM edgeai.runtime_instance WHERE id=NEW.runtime_id;
    IF r.desired_state<>'RUNNING' OR r.observed_state<>'RUNNING' OR r.producer_pod_uid IS DISTINCT FROM NEW.producer_pod_uid
        OR r.expires_at<=NEW.created_at OR NOT EXISTS(
            SELECT 1 FROM edgeai.task t JOIN edgeai.task_definition d ON d.id=t.definition_id
            JOIN edgeai.task_attempt a ON a.task_id=t.id JOIN edgeai.workflow_run w ON w.id=t.run_id
            WHERE t.id=NEW.task_id AND t.state='RUNNING' AND a.id=NEW.attempt_id AND a.state='RUNNING'
              AND w.state IN ('PENDING','RUNNING') AND d.service_profile_version_id=NEW.service_profile_version_id) THEN
        RAISE EXCEPTION 'Checkpoint requires current running producer and immutable SERVICE' USING ERRCODE='23514';
    END IF;
    SELECT * INTO previous FROM edgeai.stream_checkpoint WHERE task_id=NEW.task_id ORDER BY serial DESC LIMIT 1;
    IF NEW.previous_id IS DISTINCT FROM previous.id OR (previous.id IS NOT NULL AND
        (NEW.serial<=previous.serial OR NEW.execution_sha256<>previous.execution_sha256 OR NEW.service_profile_version_id<>previous.service_profile_version_id
         OR NEW.attempt_id<>previous.attempt_id OR NEW.state_revision<previous.state_revision OR NEW.generation_ids IS DISTINCT FROM previous.generation_ids
         OR NEW.summary_json->'manifest' IS DISTINCT FROM previous.summary_json->'manifest'
         OR (NEW.state_revision=previous.state_revision AND NEW.summary_json->>'stateSha256' IS DISTINCT FROM previous.summary_json->>'stateSha256'))) THEN
        RAISE EXCEPTION 'Checkpoint chain must advance without implicit handover' USING ERRCODE='23514';
    END IF;
    IF cardinality(NEW.generation_ids)<>(SELECT count(DISTINCT x) FROM unnest(NEW.generation_ids) x)
       OR cardinality(NEW.generation_ids)<>(SELECT count(*) FROM edgeai.data_route WHERE source_task_id=NEW.task_id OR consumer_task_id=NEW.task_id) THEN
        RAISE EXCEPTION 'Checkpoint requires the complete generation set' USING ERRCODE='23514';
    END IF;
    FOREACH gid IN ARRAY NEW.generation_ids LOOP
        SELECT * INTO g FROM edgeai.route_generation WHERE id=gid;
        IF g.id IS NULL OR g.run_id<>NEW.run_id OR g.state<>'ACTIVE' OR g.lease_until<=NEW.created_at
            OR NOT coalesce((g.consumer_task_id=NEW.task_id AND g.consumer_attempt_id=NEW.attempt_id AND g.consumer_epoch=NEW.epoch)
                OR (g.source_task_id=NEW.task_id AND g.producer_attempt_id=NEW.attempt_id AND g.producer_epoch=NEW.epoch),false) THEN
            RAISE EXCEPTION 'Checkpoint generation belongs to another or fenced producer' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF jsonb_array_length(NEW.summary_json->'routes')<>cardinality(NEW.generation_ids)
       OR (SELECT count(DISTINCT x->>'routeId') FROM jsonb_array_elements(NEW.summary_json->'routes') x)<>cardinality(NEW.generation_ids) THEN
        RAISE EXCEPTION 'Checkpoint cursor_row set must cover every route' USING ERRCODE='23514';
    END IF;
    FOR cursor_row IN SELECT value FROM jsonb_array_elements(NEW.summary_json->'routes') LOOP
        SELECT * INTO g FROM edgeai.route_generation WHERE id=ANY(NEW.generation_ids) AND route_id=(cursor_row->>'routeId')::uuid;
        IF g.id IS NULL OR NOT cursor_row ?& ARRAY['routeId','received','committed','ended']
           OR cursor_row-ARRAY['routeId','received','committed','ended']<>'{}'::jsonb
           OR (cursor_row->>'committed')::bigint<0 OR (cursor_row->>'received')::bigint<(cursor_row->>'committed')::bigint THEN
            RAISE EXCEPTION 'Invalid checkpoint cursor_row' USING ERRCODE='23514';
        END IF;
        IF previous.id IS NOT NULL THEN
            SELECT value INTO prior FROM jsonb_array_elements(previous.summary_json->'routes') WHERE value->>'routeId'=cursor_row->>'routeId';
            input=g.consumer_attempt_id=NEW.attempt_id;
            IF (cursor_row->>'committed')::bigint<(prior->>'committed')::bigint OR (cursor_row->>'received')::bigint<(prior->>'received')::bigint
               OR (prior->>'ended'='true' AND cursor_row->>'ended'<>'true')
               OR (NEW.state_revision=previous.state_revision AND
                   cursor_row->>(CASE WHEN input THEN 'committed' ELSE 'received' END) IS DISTINCT FROM prior->>(CASE WHEN input THEN 'committed' ELSE 'received' END)) THEN
                RAISE EXCEPTION 'Checkpoint cursors cannot regress or change without computation' USING ERRCODE='23514';
            END IF;
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER stream_checkpoint_immutable BEFORE INSERT OR UPDATE OR DELETE ON edgeai.stream_checkpoint
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_stream_checkpoint();
CREATE TRIGGER stream_checkpoint_no_truncate BEFORE TRUNCATE ON edgeai.stream_checkpoint
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_stream_checkpoint();
