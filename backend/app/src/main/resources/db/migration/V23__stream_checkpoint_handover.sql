-- Explicit server-verified handover; V22 remains immutable.
ALTER TABLE edgeai.stream_checkpoint ADD COLUMN handover_from_id uuid;
ALTER TABLE edgeai.stream_checkpoint ADD CONSTRAINT stream_checkpoint_handover_source
    FOREIGN KEY(handover_from_id,task_id) REFERENCES edgeai.stream_checkpoint(id,task_id);
ALTER TABLE edgeai.stream_checkpoint ADD CONSTRAINT stream_checkpoint_handover_previous
    CHECK(handover_from_id IS NULL OR (previous_id IS NOT NULL AND handover_from_id=previous_id));
CREATE OR REPLACE FUNCTION edgeai.protect_stream_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous edgeai.stream_checkpoint; r edgeai.runtime_instance; g edgeai.route_generation; gid uuid; cursor_row jsonb; prior jsonb; input boolean; old_generation edgeai.route_generation; direction text; old_bindings jsonb; new_bindings jsonb; binding jsonb; expected_producer jsonb;
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
         OR NEW.state_revision<previous.state_revision
         OR (NEW.state_revision=previous.state_revision AND NEW.summary_json->>'stateSha256' IS DISTINCT FROM previous.summary_json->>'stateSha256'))) THEN
        RAISE EXCEPTION 'Checkpoint chain must advance without implicit handover' USING ERRCODE='23514';
    END IF;
    IF NEW.handover_from_id IS NULL THEN
        IF previous.id IS NOT NULL AND (NEW.attempt_id<>previous.attempt_id OR NEW.generation_ids IS DISTINCT FROM previous.generation_ids
           OR NEW.summary_json->'manifest' IS DISTINCT FROM previous.summary_json->'manifest') THEN
            RAISE EXCEPTION 'Checkpoint scope changes require explicit verified handover' USING ERRCODE='23514';
        END IF;
    ELSE
        IF previous.id IS NULL OR NEW.handover_from_id<>previous.id OR NEW.serial<>previous.serial+1
           OR NEW.state_revision<>previous.state_revision OR NEW.summary_json-'manifest' IS DISTINCT FROM previous.summary_json-'manifest'
           OR (NEW.summary_json->'manifest')-ARRAY['inputs','outputs'] IS DISTINCT FROM (previous.summary_json->'manifest')-ARRAY['inputs','outputs']
           OR (NEW.attempt_id=previous.attempt_id AND NEW.generation_ids=previous.generation_ids) THEN
            RAISE EXCEPTION 'Handover must preserve sealed computation and cursors exactly' USING ERRCODE='23514';
        END IF;
        IF NEW.attempt_id<>previous.attempt_id AND NOT EXISTS(
            SELECT 1 FROM edgeai.runtime_instance prior_runtime WHERE prior_runtime.id=previous.runtime_id
              AND prior_runtime.desired_state='STOPPED' AND prior_runtime.observed_state='TERMINATED' AND prior_runtime.epoch<NEW.epoch) THEN
            RAISE EXCEPTION 'Handover requires confirmed old producer termination' USING ERRCODE='23514';
        END IF;
        FOREACH direction IN ARRAY ARRAY['inputs','outputs'] LOOP
            SELECT coalesce(jsonb_agg(x-ARRAY['generation','producer'] ORDER BY x->>'routeId'),'[]'::jsonb) INTO old_bindings
                FROM jsonb_array_elements(previous.summary_json->'manifest'->direction) x;
            SELECT coalesce(jsonb_agg(x-ARRAY['generation','producer'] ORDER BY x->>'routeId'),'[]'::jsonb) INTO new_bindings
                FROM jsonb_array_elements(NEW.summary_json->'manifest'->direction) x;
            IF old_bindings IS DISTINCT FROM new_bindings THEN
                RAISE EXCEPTION 'Handover cannot replace logical routes or directions' USING ERRCODE='23514';
            END IF;
        END LOOP;
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
        IF NEW.handover_from_id IS NOT NULL THEN
            SELECT * INTO old_generation FROM edgeai.route_generation WHERE id=ANY(previous.generation_ids) AND route_id=g.route_id;
            IF old_generation.id IS NULL OR (old_generation.id<>g.id AND (old_generation.state<>'CLOSED' OR old_generation.generation>=g.generation))
               OR (g.source_device_id IS NOT NULL AND (g.producer_session_id,g.producer_epoch) IS DISTINCT FROM
                   (old_generation.producer_session_id,old_generation.producer_epoch)) THEN
                RAISE EXCEPTION 'Handover requires revoked old routes and unchanged Device sessions' USING ERRCODE='23514';
            END IF;
            direction=CASE WHEN g.consumer_task_id=NEW.task_id THEN 'inputs' ELSE 'outputs' END;
            SELECT value INTO binding FROM jsonb_array_elements(NEW.summary_json->'manifest'->direction) WHERE value->>'routeId'=g.route_id::text;
            expected_producer=CASE WHEN g.source_device_id IS NOT NULL THEN
                jsonb_build_object('kind','DEVICE_SESSION','deviceId',g.source_device_id::text,'sessionId',g.producer_session_id::text,'epoch',g.producer_epoch)
                ELSE jsonb_build_object('kind','TASK_ATTEMPT','attemptId',g.producer_attempt_id::text,'epoch',g.producer_epoch) END;
            IF binding IS NULL OR binding->'generation' IS DISTINCT FROM to_jsonb(g.generation) OR binding->'producer' IS DISTINCT FROM expected_producer THEN
                RAISE EXCEPTION 'Handover manifest must use authenticated current route identities' USING ERRCODE='23514';
            END IF;
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
