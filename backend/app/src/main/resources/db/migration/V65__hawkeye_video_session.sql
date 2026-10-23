-- Hawkeye video sessions: consecutive bounded BATCH Runs (windows) of one published SERVICE workflow over one pinned camera.
-- Frames, detections and placements stay in the platform's own Run/Task/Attempt/Result records; this only orders the windows.
CREATE TABLE edgeai.hawkeye_video_session (
    id uuid PRIMARY KEY,
    idempotency_key uuid NOT NULL UNIQUE,
    request_digest varchar(71) NOT NULL CHECK (request_digest ~ '^sha256:[a-f0-9]{64}$'),
    workflow_version_id uuid NOT NULL REFERENCES edgeai.workflow_version(id),
    device_id text NOT NULL,
    device_uid uuid NOT NULL,
    -- Camera identity pinned by the first window Run (without pinnedAt); every later window must pin the same source.
    camera_source jsonb,
    max_windows integer NOT NULL CHECK (max_windows BETWEEN 1 AND 1000),
    state text NOT NULL CHECK (state IN ('RUNNING','STOPPED','COMPLETED','FAILED')),
    end_reason text CHECK (end_reason IN ('USER_STOPPED','MAX_WINDOWS','WINDOW_FAILED','WINDOW_REJECTED')),
    failure_code text,
    failure_message text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    ended_at timestamptz,
    CHECK ((state = 'RUNNING') = (ended_at IS NULL)),
    CHECK ((state = 'RUNNING') = (end_reason IS NULL)),
    CHECK ((state = 'FAILED') = (failure_code IS NOT NULL)),
    CHECK (state <> 'COMPLETED' OR camera_source IS NOT NULL)
);
-- At most one session creates windows at a time across every API instance.
CREATE UNIQUE INDEX hawkeye_video_session_single_active ON edgeai.hawkeye_video_session ((true)) WHERE state = 'RUNNING';
CREATE INDEX hawkeye_video_session_created ON edgeai.hawkeye_video_session (created_at DESC, id DESC);

CREATE TABLE edgeai.hawkeye_video_window (
    session_id uuid NOT NULL REFERENCES edgeai.hawkeye_video_session(id),
    seq integer NOT NULL CHECK (seq >= 1),
    run_id uuid NOT NULL UNIQUE REFERENCES edgeai.workflow_run(id),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (session_id, seq)
);

CREATE FUNCTION edgeai.hawkeye_video_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
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
            OR (OLD.camera_source IS NOT NULL AND NEW.camera_source IS DISTINCT FROM OLD.camera_source) THEN
        RAISE EXCEPTION 'hawkeye video session request is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER hawkeye_video_session_guard BEFORE UPDATE OR DELETE ON edgeai.hawkeye_video_session
    FOR EACH ROW EXECUTE FUNCTION edgeai.hawkeye_video_immutable();
CREATE TRIGGER hawkeye_video_window_guard BEFORE UPDATE OR DELETE ON edgeai.hawkeye_video_window
    FOR EACH ROW EXECUTE FUNCTION edgeai.hawkeye_video_immutable();
