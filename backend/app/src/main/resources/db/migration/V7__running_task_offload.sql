ALTER TABLE edgeai.task_attempt
    ADD COLUMN mode varchar(8),
    ADD COLUMN node_id uuid REFERENCES edgeai.execution_node(id),
    ADD COLUMN cause varchar(8);
UPDATE edgeai.task_attempt a SET mode=w.mode,node_id=w.node_id,
    cause=CASE WHEN a.number=1 THEN 'INITIAL' ELSE 'RETRY' END
    FROM edgeai.task t JOIN edgeai.workflow_run w ON w.id=t.run_id WHERE t.id=a.task_id;
ALTER TABLE edgeai.task_attempt ALTER COLUMN mode SET NOT NULL;
ALTER TABLE edgeai.task_attempt ALTER COLUMN cause SET NOT NULL;
ALTER TABLE edgeai.task_attempt ADD CONSTRAINT attempt_target
    CHECK((mode='AUTO' AND node_id IS NULL) OR (mode='NODE' AND node_id IS NOT NULL));
ALTER TABLE edgeai.task_attempt ADD CONSTRAINT attempt_cause CHECK(cause IN ('INITIAL','RETRY','OFFLOAD'));
ALTER TABLE edgeai.task DROP CONSTRAINT task_state_check;
ALTER TABLE edgeai.task ADD CONSTRAINT task_state_check
    CHECK(state IN ('WAITING','READY','RUNNING','RETRY_WAIT','OFFLOADING','SUCCEEDED','FAILED','CANCELLING','CANCELLED','SKIPPED'));

CREATE TABLE edgeai.task_offload (
    id uuid PRIMARY KEY, task_id uuid NOT NULL, run_id uuid NOT NULL,
    source_attempt_id uuid NOT NULL, target_attempt_id uuid,
    target_node_id uuid NOT NULL REFERENCES edgeai.execution_node(id),
    idempotency_key uuid NOT NULL UNIQUE,
    request_digest varchar(71) NOT NULL CHECK(request_digest ~ '^sha256:[a-f0-9]{64}$'),
    namespace varchar(63) NOT NULL CHECK(namespace ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    state varchar(16) NOT NULL CHECK(state IN ('DRAINING','STARTING','SUCCEEDED','FAILED','CANCELLING','CANCELLED')),
    failure_reason varchar(64),
    drain_deadline timestamptz NOT NULL,
    start_timeout_seconds integer NOT NULL CHECK(start_timeout_seconds BETWEEN 1 AND 600),
    start_deadline timestamptz,
    created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
    FOREIGN KEY(task_id,run_id) REFERENCES edgeai.task(id,run_id),
    FOREIGN KEY(source_attempt_id,task_id) REFERENCES edgeai.task_attempt(id,task_id),
    FOREIGN KEY(target_attempt_id,task_id) REFERENCES edgeai.task_attempt(id,task_id),
    CHECK(state NOT IN ('STARTING','SUCCEEDED') OR (target_attempt_id IS NOT NULL AND start_deadline IS NOT NULL)),
    CHECK(target_attempt_id IS NULL OR target_attempt_id<>source_attempt_id)
);
CREATE UNIQUE INDEX task_one_active_offload ON edgeai.task_offload(task_id)
    WHERE state IN ('DRAINING','STARTING','CANCELLING');
CREATE INDEX task_offload_pending ON edgeai.task_offload(namespace,updated_at,id)
    WHERE state IN ('DRAINING','STARTING','CANCELLING');
CREATE INDEX task_offload_history ON edgeai.task_offload(task_id,created_at DESC,id);
