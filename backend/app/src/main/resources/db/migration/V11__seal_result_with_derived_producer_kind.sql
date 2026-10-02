-- Stored generated columns are computed after BEFORE triggers. Compare the immutable source
-- columns, not NEW.producer_kind (not available yet), when sealing a verified Result.
CREATE OR REPLACE FUNCTION edgeai.protect_task_result() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NOT NEW.committed THEN RETURN NEW; END IF;
    END IF;
    IF TG_OP='UPDATE' THEN
        IF NOT OLD.committed AND NEW.committed AND
           (to_jsonb(OLD)-ARRAY['committed','producer_kind'])=(to_jsonb(NEW)-ARRAY['committed','producer_kind']) THEN
            IF NOT EXISTS(SELECT 1 FROM edgeai.result_artifact WHERE result_id=NEW.id) THEN
                RAISE EXCEPTION 'Result requires verified artifacts' USING ERRCODE='23514';
            END IF;
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'Committed results are immutable' USING ERRCODE='23514';
END;
$$;
