-- Revisit already published completion groups to retain their checkpoint ancestry.
-- The original V37 document, grant, object key and completion flag stay immutable.
ALTER TABLE edgeai.stream_completion_publication
    ADD COLUMN checkpoint_history_completed boolean NOT NULL DEFAULT false;
DROP INDEX edgeai.stream_completion_publication_pending;
CREATE INDEX stream_completion_publication_pending
    ON edgeai.stream_completion_publication(available_at,id)
    WHERE NOT completed OR NOT checkpoint_history_completed;

CREATE OR REPLACE FUNCTION edgeai.protect_stream_completion_publication() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'UPDATE' THEN RAISE EXCEPTION 'Stream completion publication history is immutable' USING ERRCODE='23514'; END IF;
    IF to_jsonb(NEW)-ARRAY['completed','checkpoint_history_completed','attempts','available_at','lease_owner','lease_until','updated_at'] IS DISTINCT FROM
        to_jsonb(OLD)-ARRAY['completed','checkpoint_history_completed','attempts','available_at','lease_owner','lease_until','updated_at']
        OR (OLD.completed AND NOT NEW.completed)
        OR (OLD.checkpoint_history_completed AND NOT NEW.checkpoint_history_completed)
        OR NEW.attempts<OLD.attempts THEN
        RAISE EXCEPTION 'Stream completion authority is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
