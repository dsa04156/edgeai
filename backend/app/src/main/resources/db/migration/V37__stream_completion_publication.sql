-- Capture the sealed connected component in the same transaction as its grant.
-- Publication is independent of producer lifetime and never creates a new grant.
CREATE TABLE edgeai.stream_completion_publication (
    id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES edgeai.workflow_run(id),
    namespace varchar(63) NOT NULL, attempt_ids uuid[] NOT NULL,
    granted_at timestamptz NOT NULL, document jsonb NOT NULL,
    completed boolean NOT NULL DEFAULT false,
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
    available_at timestamptz NOT NULL, lease_owner uuid, lease_until timestamptz,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    CHECK(cardinality(attempt_ids) BETWEEN 1 AND 128 AND id=attempt_ids[1]),
    CHECK(jsonb_typeof(document)='object' AND octet_length(document::text)<=16777216),
    CHECK(document->>'apiVersion'='edgeai.stream.completion/v1' AND document->>'id'=id::text
        AND document->>'runId'=run_id::text AND document->>'namespace'=namespace),
    CHECK((lease_owner IS NULL)=(lease_until IS NULL))
);
CREATE INDEX stream_completion_publication_pending ON edgeai.stream_completion_publication(available_at,id) WHERE NOT completed;
CREATE INDEX stream_completion_publication_attempts ON edgeai.stream_completion_publication USING gin(attempt_ids);

CREATE FUNCTION edgeai.capture_stream_completion(anchor uuid) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE tid uuid; rid uuid; members uuid[]; actors uuid[]; gids uuid[]; moment timestamptz;
    identity uuid; runtime_namespace text; v_broker_digest text; membership edgeai.stream_run_binding; value jsonb;
BEGIN
    SELECT a.task_id,t.run_id INTO tid,rid FROM edgeai.task_attempt a JOIN edgeai.task t ON t.id=a.task_id
        JOIN edgeai.stream_task_completion c ON c.attempt_id=a.id WHERE a.id=anchor AND c.granted_at IS NOT NULL;
    IF tid IS NULL THEN RETURN NULL; END IF;
    PERFORM 1 FROM edgeai.workflow_run WHERE id=rid FOR UPDATE;
    WITH RECURSIVE edges AS (
        SELECT source_task_id a,consumer_task_id b FROM edgeai.data_route WHERE run_id=rid AND source_task_id IS NOT NULL
        UNION SELECT x.consumer_task_id,y.consumer_task_id FROM edgeai.data_route x JOIN edgeai.data_route y
            ON x.run_id=y.run_id AND x.source_device_id=y.source_device_id WHERE x.run_id=rid AND x.source_device_id IS NOT NULL
    ), undirected AS (SELECT a,b FROM edges UNION SELECT b,a FROM edges), component(task_id) AS (
        SELECT tid UNION SELECT e.b FROM component c JOIN undirected e ON e.a=c.task_id
    ) SELECT array_agg(task_id ORDER BY task_id::text) INTO members FROM component;
    SELECT array_agg(c.attempt_id ORDER BY c.attempt_id::text),min(c.granted_at) INTO actors,moment
        FROM edgeai.stream_task_completion c JOIN edgeai.task_attempt a ON a.id=c.attempt_id
        WHERE a.task_id=ANY(members) AND c.granted_at IS NOT NULL;
    -- Historical partial/inconsistent grants stay unpublishable; no completion is inferred.
    IF cardinality(actors) IS DISTINCT FROM cardinality(members)
        OR (SELECT count(DISTINCT a.task_id) FROM edgeai.task_attempt a WHERE a.id=ANY(actors))<>cardinality(members)
        OR EXISTS(SELECT 1 FROM edgeai.stream_task_completion WHERE attempt_id=ANY(actors) AND granted_at<>moment) THEN RETURN NULL; END IF;
    identity=actors[1];
    IF EXISTS(SELECT 1 FROM edgeai.stream_completion_publication WHERE id=identity) THEN RETURN identity; END IF;
    SELECT * INTO membership FROM edgeai.stream_run_binding WHERE run_id=rid;
    IF membership.run_id IS NULL THEN RETURN NULL; END IF;
    SELECT array_agg(DISTINCT g ORDER BY g) INTO gids FROM edgeai.stream_checkpoint p
        JOIN edgeai.stream_task_completion c ON c.checkpoint_id=p.id CROSS JOIN unnest(p.generation_ids) g
        WHERE c.attempt_id=ANY(actors);
    SELECT min(r.namespace) INTO runtime_namespace FROM edgeai.runtime_instance r WHERE r.attempt_id=ANY(actors);
    SELECT min(g.broker_digest) INTO v_broker_digest FROM edgeai.route_generation g WHERE g.id=ANY(gids);
    IF (SELECT count(DISTINCT r.namespace) FROM edgeai.runtime_instance r WHERE r.attempt_id=ANY(actors))<>1
        OR (SELECT count(DISTINCT g.broker_digest) FROM edgeai.route_generation g WHERE g.id=ANY(gids))<>1
        OR EXISTS(SELECT 1 FROM edgeai.stream_run_configuration c WHERE c.run_id=rid
            AND (c.namespace<>runtime_namespace OR c.broker_digest<>v_broker_digest)) THEN RETURN NULL; END IF;
    IF EXISTS(SELECT 1 FROM edgeai.route_generation g LEFT JOIN edgeai.stream_device_completion d ON d.generation_id=g.id
        WHERE g.id=ANY(gids) AND g.source_device_id IS NOT NULL AND (d.granted_at IS NULL OR d.granted_at<>moment)) THEN RETURN NULL; END IF;
    value=jsonb_build_object('apiVersion','edgeai.stream.completion/v1','id',identity,'runId',rid,
        'namespace',runtime_namespace,'brokerDigest',v_broker_digest,'routeDigest',membership.route_digest,
        'taskIds',to_jsonb(members),'attemptIds',to_jsonb(actors),'grantedAt',moment,
        'taskCompletions',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.attempt_id::text) FROM edgeai.stream_task_completion c WHERE c.attempt_id=ANY(actors)),
        'deviceCompletions',(SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY c.generation_id::text),'[]'::jsonb)
            FROM edgeai.stream_device_completion c WHERE c.generation_id=ANY(gids)),
        'checkpoints',(SELECT jsonb_agg(to_jsonb(p) ORDER BY p.task_id::text) FROM edgeai.stream_checkpoint p
            JOIN edgeai.stream_task_completion c ON c.checkpoint_id=p.id WHERE c.attempt_id=ANY(actors)),
        'generations',(SELECT jsonb_agg(to_jsonb(g)-'state' ORDER BY g.id::text) FROM edgeai.route_generation g WHERE g.id=ANY(gids)),
        'routes',(SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id::text) FROM edgeai.data_route r
            WHERE r.id IN (SELECT route_id FROM edgeai.route_generation WHERE id=ANY(gids))),
        'producers',(SELECT jsonb_agg(jsonb_build_object('runtimeId',r.id,'taskId',r.task_id,'attemptId',r.attempt_id,
            'epoch',r.epoch,'kind',r.runtime_kind,'podUid',r.producer_pod_uid,'nodeUid',r.node_uid,'nodeName',r.node_name,
            'jobName',r.job_name,'jobUid',r.job_uid,'vdId',r.vd_id,
            'startKey','authority/'||(CASE WHEN r.runtime_kind='VD' THEN 'vd-task-start/' ELSE 'runtime-start/' END)||r.id::text||'.json')
            ORDER BY r.attempt_id::text) FROM edgeai.runtime_instance r WHERE r.attempt_id=ANY(actors)));
    INSERT INTO edgeai.stream_completion_publication(id,run_id,namespace,attempt_ids,granted_at,document,available_at,created_at,updated_at)
        VALUES(identity,rid,runtime_namespace,actors,moment,value,transaction_timestamp(),transaction_timestamp(),transaction_timestamp());
    RETURN identity;
END; $$;

CREATE FUNCTION edgeai.enqueue_stream_completion() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor uuid;
BEGIN
    IF NEW.granted_at IS NOT NULL THEN
        IF TG_TABLE_NAME='stream_task_completion' THEN actor=NEW.attempt_id;
        ELSE SELECT consumer_attempt_id INTO actor FROM edgeai.route_generation WHERE id=NEW.generation_id; END IF;
        PERFORM edgeai.capture_stream_completion(actor);
    END IF;
    RETURN NEW;
END; $$;
CREATE CONSTRAINT TRIGGER stream_task_completion_publication AFTER UPDATE ON edgeai.stream_task_completion
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION edgeai.enqueue_stream_completion();
CREATE CONSTRAINT TRIGGER stream_device_completion_publication AFTER UPDATE ON edgeai.stream_device_completion
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION edgeai.enqueue_stream_completion();

-- Existing complete barriers are facts too. Never backfill a partial component.
DO $$ DECLARE actor uuid; BEGIN
    FOR actor IN SELECT attempt_id FROM edgeai.stream_task_completion WHERE granted_at IS NOT NULL ORDER BY attempt_id LOOP
        PERFORM edgeai.capture_stream_completion(actor);
    END LOOP;
END; $$;

CREATE FUNCTION edgeai.protect_stream_completion_publication() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'UPDATE' THEN RAISE EXCEPTION 'Stream completion publication history is immutable' USING ERRCODE='23514'; END IF;
    IF to_jsonb(NEW)-ARRAY['completed','attempts','available_at','lease_owner','lease_until','updated_at'] IS DISTINCT FROM
        to_jsonb(OLD)-ARRAY['completed','attempts','available_at','lease_owner','lease_until','updated_at']
        OR (OLD.completed AND NOT NEW.completed) OR NEW.attempts<OLD.attempts THEN
        RAISE EXCEPTION 'Stream completion authority is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER stream_completion_publication_immutable BEFORE UPDATE OR DELETE ON edgeai.stream_completion_publication
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_stream_completion_publication();
CREATE TRIGGER stream_completion_publication_no_truncate BEFORE TRUNCATE ON edgeai.stream_completion_publication
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_stream_completion_publication();
