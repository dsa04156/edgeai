-- Hawkeye video sessions of a continuous-observation (snapshot) workflow version: the observer instance (epoch) the
-- session started is recorded once; a different epoch later means the observer lost its tracker state.
ALTER TABLE edgeai.hawkeye_video_session ADD COLUMN observer_epoch text, ADD COLUMN observer_started_at timestamptz,
    ADD CHECK ((observer_epoch IS NULL) = (observer_started_at IS NULL));
ALTER TABLE edgeai.hawkeye_video_session DROP CONSTRAINT hawkeye_video_session_end_reason_check;
ALTER TABLE edgeai.hawkeye_video_session ADD CONSTRAINT hawkeye_video_session_end_reason_check
    CHECK (end_reason IN ('USER_STOPPED','MAX_WINDOWS','WINDOW_FAILED','WINDOW_REJECTED','OBSERVER_FAILED'));

CREATE OR REPLACE FUNCTION edgeai.hawkeye_video_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'hawkeye_video_window' THEN
        RAISE EXCEPTION 'hawkeye video window is immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' OR OLD.ended_at IS NOT NULL THEN
        RAISE EXCEPTION 'ended hawkeye video session is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id OR NEW.idempotency_key <> OLD.idempotency_key OR NEW.request_digest <> OLD.request_digest
            OR NEW.workflow_version_id <> OLD.workflow_version_id OR NEW.device_id <> OLD.device_id
            OR NEW.device_uid <> OLD.device_uid OR NEW.max_windows <> OLD.max_windows OR NEW.created_at <> OLD.created_at
            OR (OLD.camera_source IS NOT NULL AND NEW.camera_source IS DISTINCT FROM OLD.camera_source)
            OR (OLD.observer_epoch IS NOT NULL AND (NEW.observer_epoch IS DISTINCT FROM OLD.observer_epoch
                OR NEW.observer_started_at IS DISTINCT FROM OLD.observer_started_at)) THEN
        RAISE EXCEPTION 'hawkeye video session request is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
