CREATE TABLE edgeai.processing_retired_session (
    session_id uuid PRIMARY KEY, pod_uid uuid NOT NULL, retired_at timestamptz NOT NULL
);
