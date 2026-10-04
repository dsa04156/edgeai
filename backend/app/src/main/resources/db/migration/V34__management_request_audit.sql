CREATE TABLE edgeai.management_audit_request (
    id uuid PRIMARY KEY,
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    method varchar(16) NOT NULL CHECK (method ~ '^[A-Z]{1,16}$'),
    operation varchar(80) NOT NULL CHECK (operation ~ '^[a-zA-Z][a-zA-Z0-9]{0,79}$'),
    route_template varchar(200) NOT NULL,
    target_id uuid,
    related_id uuid
);
CREATE INDEX management_audit_request_started ON edgeai.management_audit_request(started_at DESC, id DESC);

CREATE TABLE edgeai.management_audit_outcome (
    request_id uuid PRIMARY KEY REFERENCES edgeai.management_audit_request(id),
    completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    http_status integer NOT NULL CHECK (http_status BETWEEN 100 AND 599),
    disposition varchar(24) NOT NULL CHECK (disposition IN ('HTTP_COMPLETED', 'HANDLER_FAILED')),
    actor_type varchar(20) NOT NULL CHECK (actor_type IN ('LOCAL_BASIC', 'UNAUTHENTICATED')),
    actor_subject varchar(256),
    subject_format varchar(8) NOT NULL CHECK (subject_format IN ('NAME', 'SHA256', 'NONE')),
    CHECK ((actor_type = 'UNAUTHENTICATED' AND actor_subject IS NULL AND subject_format = 'NONE') OR
           (actor_type = 'LOCAL_BASIC' AND actor_subject IS NOT NULL AND
            ((subject_format = 'NAME' AND length(actor_subject) BETWEEN 1 AND 256) OR
             (subject_format = 'SHA256' AND actor_subject ~ '^[a-f0-9]{64}$'))))
);

CREATE FUNCTION edgeai.reject_management_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Management audit records are append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER immutable_management_audit_request_rows BEFORE UPDATE OR DELETE ON edgeai.management_audit_request
    FOR EACH ROW EXECUTE FUNCTION edgeai.reject_management_audit_mutation();
CREATE TRIGGER immutable_management_audit_request_truncate BEFORE TRUNCATE ON edgeai.management_audit_request
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.reject_management_audit_mutation();
CREATE TRIGGER immutable_management_audit_outcome_rows BEFORE UPDATE OR DELETE ON edgeai.management_audit_outcome
    FOR EACH ROW EXECUTE FUNCTION edgeai.reject_management_audit_mutation();
CREATE TRIGGER immutable_management_audit_outcome_truncate BEFORE TRUNCATE ON edgeai.management_audit_outcome
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.reject_management_audit_mutation();
