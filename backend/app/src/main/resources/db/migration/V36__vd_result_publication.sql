-- Reuse the independent publication queue for immutable VD child Results.
-- Existing physical commands and completed publication rows keep their meaning.
CREATE OR REPLACE FUNCTION edgeai.enqueue_runtime_result_publication() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.committed AND NOT OLD.committed AND NEW.remote_allocation_id IS NULL THEN
        INSERT INTO edgeai.runtime_result_publication(result_id,runtime_id,available_at,created_at,updated_at)
            VALUES(NEW.id,NEW.runtime_id,transaction_timestamp(),transaction_timestamp(),transaction_timestamp());
    END IF;
    RETURN NEW;
END;
$$;

-- These are already accepted Results, including stopped supervisors. No start admission is invented.
INSERT INTO edgeai.runtime_result_publication(result_id,runtime_id,available_at,created_at,updated_at)
    SELECT id,runtime_id,transaction_timestamp(),transaction_timestamp(),transaction_timestamp()
    FROM edgeai.task_result WHERE committed AND remote_allocation_id IS NULL AND vd_runtime_id IS NOT NULL
    ON CONFLICT (result_id) DO NOTHING;
