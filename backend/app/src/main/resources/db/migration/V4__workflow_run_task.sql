CREATE TABLE edgeai.workflow (
    id uuid PRIMARY KEY, workflow_key varchar(100) COLLATE "C" NOT NULL UNIQUE
        CHECK(workflow_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    display_name varchar(128) NOT NULL CHECK(length(trim(display_name))>0),
    creation_digest varchar(71) NOT NULL CHECK(creation_digest ~ '^sha256:[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE edgeai.workflow_version (
    id uuid PRIMARY KEY, workflow_id uuid NOT NULL REFERENCES edgeai.workflow(id),
    version varchar(32) COLLATE "C" NOT NULL CHECK(version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
    dag jsonb NOT NULL CHECK(jsonb_typeof(dag)='object'),
    digest varchar(71) NOT NULL CHECK(digest ~ '^sha256:[a-f0-9]{64}$'),
    published boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(workflow_id,version), UNIQUE(id,published)
);
CREATE INDEX workflow_version_history ON edgeai.workflow_version(workflow_id,created_at DESC,id);
CREATE TABLE edgeai.task_definition (
    id uuid PRIMARY KEY, workflow_version_id uuid NOT NULL REFERENCES edgeai.workflow_version(id),
    task_key varchar(100) COLLATE "C" NOT NULL CHECK(task_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    service_profile_version_id uuid NOT NULL, profile_kind varchar(7) NOT NULL DEFAULT 'SERVICE' CHECK(profile_kind='SERVICE'),
    parameters jsonb NOT NULL CHECK(jsonb_typeof(parameters)='object'),
    FOREIGN KEY(service_profile_version_id,profile_kind) REFERENCES edgeai.profile_version(id,kind),
    UNIQUE(workflow_version_id,task_key), UNIQUE(id,workflow_version_id)
);
CREATE TABLE edgeai.task_dependency (
    workflow_version_id uuid NOT NULL REFERENCES edgeai.workflow_version(id),
    from_task_id uuid NOT NULL, to_task_id uuid NOT NULL,
    from_port varchar(100) NOT NULL CHECK(from_port ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    to_port varchar(100) NOT NULL CHECK(to_port ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    mode varchar(8) NOT NULL CHECK(mode IN ('BATCH','STREAM')),
    PRIMARY KEY(workflow_version_id,to_task_id,to_port), CHECK(from_task_id<>to_task_id),
    FOREIGN KEY(from_task_id,workflow_version_id) REFERENCES edgeai.task_definition(id,workflow_version_id),
    FOREIGN KEY(to_task_id,workflow_version_id) REFERENCES edgeai.task_definition(id,workflow_version_id)
);
CREATE FUNCTION edgeai.protect_workflow_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF NOT OLD.published AND NEW.published
           AND (to_jsonb(OLD)-'published')=(to_jsonb(NEW)-'published') THEN RETURN NEW; END IF;
    END IF;
    RAISE EXCEPTION 'Published workflow definitions are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER workflow_version_immutable BEFORE UPDATE OR DELETE ON edgeai.workflow_version
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_workflow_version();
CREATE TRIGGER workflow_version_no_truncate BEFORE TRUNCATE ON edgeai.workflow_version
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_workflow_version();
CREATE FUNCTION edgeai.protect_workflow_child() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sealed boolean;
BEGIN
    IF TG_OP='INSERT' THEN
        SELECT published INTO sealed FROM edgeai.workflow_version WHERE id=NEW.workflow_version_id FOR SHARE;
        IF sealed=false THEN RETURN NEW; END IF;
    END IF;
    RAISE EXCEPTION 'Published workflow definitions are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER task_definition_immutable BEFORE INSERT OR UPDATE OR DELETE ON edgeai.task_definition
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_workflow_child();
CREATE TRIGGER task_dependency_immutable BEFORE INSERT OR UPDATE OR DELETE ON edgeai.task_dependency
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_workflow_child();
CREATE TRIGGER task_definition_no_truncate BEFORE TRUNCATE ON edgeai.task_definition
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_workflow_child();
CREATE TRIGGER task_dependency_no_truncate BEFORE TRUNCATE ON edgeai.task_dependency
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_workflow_child();

CREATE TABLE edgeai.workflow_run (
    id uuid PRIMARY KEY, workflow_version_id uuid NOT NULL, version_published boolean NOT NULL DEFAULT true CHECK(version_published),
    idempotency_key uuid NOT NULL UNIQUE, request_digest varchar(71) NOT NULL CHECK(request_digest ~ '^sha256:[a-f0-9]{64}$'),
    mode varchar(8) NOT NULL CHECK(mode IN ('AUTO','NODE')), node_id uuid REFERENCES edgeai.execution_node(id),
    parameters jsonb NOT NULL CHECK(jsonb_typeof(parameters)='object'),
    state varchar(16) NOT NULL CHECK(state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLING','CANCELLED')),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    CHECK((mode='AUTO' AND node_id IS NULL) OR (mode='NODE' AND node_id IS NOT NULL)),
    FOREIGN KEY(workflow_version_id,version_published) REFERENCES edgeai.workflow_version(id,published),
    UNIQUE(id,workflow_version_id)
);
CREATE INDEX workflow_run_history ON edgeai.workflow_run(created_at DESC,id);
CREATE TABLE edgeai.task (
    id uuid PRIMARY KEY, run_id uuid NOT NULL, workflow_version_id uuid NOT NULL, definition_id uuid NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('WAITING','READY','RUNNING','SUCCEEDED','FAILED','CANCELLING','CANCELLED','SKIPPED')),
    cancellation_reason varchar(64), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE(run_id,definition_id),
    FOREIGN KEY(run_id,workflow_version_id) REFERENCES edgeai.workflow_run(id,workflow_version_id),
    FOREIGN KEY(definition_id,workflow_version_id) REFERENCES edgeai.task_definition(id,workflow_version_id)
);
CREATE INDEX task_run ON edgeai.task(run_id,id);
CREATE TABLE edgeai.task_attempt (
    id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES edgeai.task(id),
    number integer NOT NULL CHECK(number>0), epoch bigint NOT NULL CHECK(epoch BETWEEN 1 AND 9007199254740991),
    state varchar(16) NOT NULL CHECK(state IN ('QUEUED','DISPATCHING','RUNNING','SUCCEEDED','FAILED','CANCELLING','CANCELLED','OFFLOADED')),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE(task_id,number), UNIQUE(task_id,epoch)
);
CREATE UNIQUE INDEX task_one_active_attempt ON edgeai.task_attempt(task_id)
    WHERE state IN ('QUEUED','DISPATCHING','RUNNING','CANCELLING');
