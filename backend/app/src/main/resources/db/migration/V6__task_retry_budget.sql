ALTER TABLE edgeai.workflow_run
    ADD COLUMN retry_max_attempts integer NOT NULL DEFAULT 1 CHECK(retry_max_attempts BETWEEN 1 AND 8),
    ADD COLUMN retry_backoff_seconds integer NOT NULL DEFAULT 1 CHECK(retry_backoff_seconds BETWEEN 1 AND 300),
    ADD COLUMN retry_max_elapsed_seconds integer NOT NULL DEFAULT 86400 CHECK(retry_max_elapsed_seconds BETWEEN 1 AND 86400),
    ADD COLUMN retry_on text[] NOT NULL DEFAULT '{}' CHECK(retry_on <@ ARRAY['WORKLOAD_FAILED','TIMEOUT','STORAGE_FAILED','RUNNER_FAILED','DISPATCH_TIMEOUT','RUNTIME_TIMEOUT','RUNTIME_LOST','JOB_FAILED']::text[]),
    ADD CONSTRAINT retry_enabled_codes CHECK(retry_max_attempts=1 OR cardinality(retry_on)>0);
ALTER TABLE edgeai.task DROP CONSTRAINT task_state_check;
ALTER TABLE edgeai.task ADD CONSTRAINT task_state_check
    CHECK(state IN ('WAITING','READY','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLING','CANCELLED','SKIPPED'));
ALTER TABLE edgeai.task_attempt ADD CONSTRAINT task_attempt_task_identity UNIQUE(id,task_id);
CREATE TABLE edgeai.task_retry (
    task_id uuid PRIMARY KEY REFERENCES edgeai.task(id),
    failed_attempt_id uuid NOT NULL,
    namespace varchar(63) NOT NULL CHECK(namespace ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    available_at timestamptz NOT NULL,
    deadline timestamptz NOT NULL CHECK(available_at<deadline),
    FOREIGN KEY(failed_attempt_id,task_id) REFERENCES edgeai.task_attempt(id,task_id)
);
CREATE INDEX task_retry_due ON edgeai.task_retry(namespace,available_at,task_id);
