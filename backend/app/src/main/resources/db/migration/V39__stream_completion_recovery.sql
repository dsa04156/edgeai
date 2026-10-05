-- Historical insertion is confined to an explicitly marked restored database and
-- the exact transaction-local rows verified by the recovery CLI. No live lease,
-- producer activation, UPDATE, DELETE or TRUNCATE is authorized by this scope.
CREATE FUNCTION edgeai.stream_recovery_row(kind text, candidate jsonb) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE scope jsonb; expected jsonb; marker text; oid_text text;
BEGIN
    IF coalesce(current_setting('edgeai.stream_history_recovery',true),'')<>'on' THEN RETURN false; END IF;
    SELECT oid::text,shobj_description(oid,'pg_database') INTO oid_text,marker FROM pg_database WHERE datname=current_database();
    IF current_database() !~ '^edgeai_restore_[a-z0-9_]+$' OR marker IS NULL OR marker !~ '^edgeai-restore:[a-f0-9]{32}$'
        OR to_regclass('pg_temp.edgeai_stream_recovery_scope') IS NULL THEN
        RAISE EXCEPTION 'Historical STREAM insertion requires an explicit restored database transaction' USING ERRCODE='23514';
    END IF;
    EXECUTE 'SELECT value FROM pg_temp.edgeai_stream_recovery_scope' INTO STRICT scope;
    IF scope->>'targetDatabase' IS DISTINCT FROM current_database() OR scope->>'databaseOid' IS DISTINCT FROM oid_text
        OR scope->>'marker' IS DISTINCT FROM marker OR scope->>'transactionId' IS DISTINCT FROM txid_current()::text THEN
        RAISE EXCEPTION 'Historical STREAM scope belongs to another restore or transaction' USING ERRCODE='23514';
    END IF;
    IF kind='generation' THEN
        SELECT to_jsonb(g)-'state' INTO STRICT expected FROM jsonb_populate_recordset(NULL::edgeai.route_generation,scope->'generations') g
            WHERE g.id=(candidate->>'id')::uuid;
        IF (candidate-ARRAY['state','updated_at','fenced_at','fence_reason','closed_at']) IS DISTINCT FROM
            (expected-ARRAY['updated_at','fenced_at','fence_reason','closed_at'])
            OR expected->>'activated_at' IS NULL OR expected->>'fenced_at' IS NOT NULL OR expected->>'closed_at' IS NOT NULL
            OR candidate->>'fence_reason' IS DISTINCT FROM 'REPLACED'
            OR (candidate->>'fenced_at')::timestamptz IS DISTINCT FROM transaction_timestamp()
            OR (candidate->>'closed_at')::timestamptz IS DISTINCT FROM transaction_timestamp()
            OR (candidate->>'updated_at')::timestamptz IS DISTINCT FROM transaction_timestamp() THEN
            RAISE EXCEPTION 'Historical generation must preserve original authority and be retired in this transaction' USING ERRCODE='23514';
        END IF;
    ELSIF kind='checkpoint' THEN
        SELECT to_jsonb(c) INTO STRICT expected FROM jsonb_populate_recordset(NULL::edgeai.stream_checkpoint,scope->'checkpoints') c
            WHERE c.id=(candidate->>'id')::uuid;
        IF candidate IS DISTINCT FROM expected THEN
            RAISE EXCEPTION 'Historical checkpoint differs from its exact independent receipt' USING ERRCODE='23514';
        END IF;
    ELSE RAISE EXCEPTION 'Unknown historical STREAM row kind' USING ERRCODE='23514';
    END IF;
    RETURN true;
END;
$$;

CREATE OR REPLACE FUNCTION edgeai.protect_route_generation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE route edgeai.data_route; previous bigint; check_actors boolean; recovering boolean;
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
        recovering=edgeai.stream_recovery_row('generation',to_jsonb(NEW));
        SELECT coalesce(max(generation),0) INTO previous FROM edgeai.route_generation WHERE route_id=NEW.route_id;
        IF recovering THEN
            IF NEW.generation<>previous+1 OR NOT EXISTS(SELECT FROM edgeai.stream_run_binding WHERE run_id=NEW.run_id)
                OR EXISTS(SELECT FROM edgeai.runtime_instance WHERE attempt_id IN (NEW.consumer_attempt_id,NEW.producer_attempt_id)
                    AND (desired_state<>'STOPPED' OR observed_state<>'TERMINATED'))
                OR NOT EXISTS(SELECT FROM edgeai.runtime_instance WHERE attempt_id=NEW.consumer_attempt_id
                    AND desired_state='STOPPED' AND observed_state='TERMINATED' AND producer_pod_uid IS NOT NULL)
                OR (NEW.producer_attempt_id IS NOT NULL AND NOT EXISTS(SELECT FROM edgeai.runtime_instance WHERE attempt_id=NEW.producer_attempt_id
                    AND desired_state='STOPPED' AND observed_state='TERMINATED' AND producer_pod_uid IS NOT NULL)) THEN
                RAISE EXCEPTION 'Historical generation requires complete original order and retired producers' USING ERRCODE='23514';
            END IF;
            RETURN NEW;
        END IF;
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

CREATE OR REPLACE FUNCTION edgeai.protect_stream_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous edgeai.stream_checkpoint; r edgeai.runtime_instance; g edgeai.route_generation; gid uuid; cursor_row jsonb; prior jsonb; input boolean; old_generation edgeai.route_generation; direction text; old_bindings jsonb; new_bindings jsonb; binding jsonb; expected_producer jsonb; recovering boolean;
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Verified checkpoints are immutable' USING ERRCODE='23514'; END IF;
    recovering=edgeai.stream_recovery_row('checkpoint',to_jsonb(NEW));
    PERFORM 1 FROM edgeai.workflow_run WHERE id=NEW.run_id FOR UPDATE;
    SELECT * INTO r FROM edgeai.runtime_instance WHERE id=NEW.runtime_id;
    IF (NOT recovering AND (r.desired_state<>'RUNNING' OR r.observed_state<>'RUNNING'))
        OR (recovering AND (r.desired_state<>'STOPPED' OR r.observed_state<>'TERMINATED'))
        OR r.producer_pod_uid IS DISTINCT FROM NEW.producer_pod_uid
        OR r.expires_at<=NEW.created_at OR NOT EXISTS(
            SELECT 1 FROM edgeai.task t JOIN edgeai.task_definition d ON d.id=t.definition_id
            JOIN edgeai.task_attempt a ON a.task_id=t.id JOIN edgeai.workflow_run w ON w.id=t.run_id
            WHERE t.id=NEW.task_id AND a.id=NEW.attempt_id AND a.epoch=NEW.epoch
              AND (recovering OR (t.state='RUNNING' AND a.state='RUNNING' AND w.state IN ('PENDING','RUNNING')))
              AND d.service_profile_version_id=NEW.service_profile_version_id) THEN
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
        IF g.id IS NULL OR g.run_id<>NEW.run_id
            OR (NOT recovering AND g.state<>'ACTIVE') OR (recovering AND (g.state<>'CLOSED' OR g.fenced_at IS NULL))
            OR g.lease_until<=NEW.created_at OR (recovering AND g.created_at>NEW.created_at)
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
