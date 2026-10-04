-- A separate durable queue: physical Job retirement must never acknowledge S3 publication.
CREATE TABLE edgeai.runtime_result_publication (
    result_id uuid PRIMARY KEY REFERENCES edgeai.task_result(id),
    runtime_id uuid NOT NULL UNIQUE REFERENCES edgeai.runtime_instance(id),
    completed boolean NOT NULL DEFAULT false,
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
    available_at timestamptz NOT NULL, lease_owner uuid, lease_until timestamptz,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    CHECK((lease_owner IS NULL)=(lease_until IS NULL))
);
CREATE INDEX runtime_result_publication_pending ON edgeai.runtime_result_publication(available_at,result_id) WHERE NOT completed;

CREATE FUNCTION edgeai.enqueue_runtime_result_publication() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.committed AND NOT OLD.committed AND NEW.remote_allocation_id IS NULL AND NEW.vd_runtime_id IS NULL THEN
        INSERT INTO edgeai.runtime_result_publication(result_id,runtime_id,available_at,created_at,updated_at)
            VALUES(NEW.id,NEW.runtime_id,transaction_timestamp(),transaction_timestamp(),transaction_timestamp());
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER enqueue_runtime_result_publication AFTER UPDATE OF committed ON edgeai.task_result
    FOR EACH ROW EXECUTE FUNCTION edgeai.enqueue_runtime_result_publication();

-- Retain existing committed results too; this does not invent an earlier start admission.
INSERT INTO edgeai.runtime_result_publication(result_id,runtime_id,available_at,created_at,updated_at)
    SELECT id,runtime_id,transaction_timestamp(),transaction_timestamp(),transaction_timestamp()
    FROM edgeai.task_result WHERE committed AND remote_allocation_id IS NULL AND vd_runtime_id IS NULL;
