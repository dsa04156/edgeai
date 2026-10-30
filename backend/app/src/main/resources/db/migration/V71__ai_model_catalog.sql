-- AI model catalog: immutable execution configurations of model versions (image digest + platform + resource requirements).
-- The registry stores image bytes; this table stores only the verified reference, metadata and requirement snapshot.
-- Registration does not publish SERVICE profiles, start workloads or reserve resources.
CREATE TABLE edgeai.ai_model_execution (
    id uuid PRIMARY KEY,
    model_key varchar(100) NOT NULL CHECK(model_key ~ '^[a-z][a-z0-9._-]{0,99}$'),
    model_version varchar(64) NOT NULL CHECK(model_version ~ '^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$'),
    config_name varchar(63) NOT NULL CHECK(config_name ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    display_name varchar(200) NOT NULL,
    registry_repository varchar(255) NOT NULL,
    image_digest varchar(71) NOT NULL CHECK(image_digest ~ '^sha256:[a-f0-9]{64}$'),
    resource_profile_id uuid REFERENCES edgeai.resource_profile(id),
    measurement_status varchar(16) NOT NULL CHECK(measurement_status IN ('MEASURED','PARTIAL','UNMEASURED')),
    idempotency_key uuid NOT NULL UNIQUE,
    request_fingerprint varchar(71) NOT NULL CHECK(request_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    digest varchar(71) NOT NULL CHECK(digest ~ '^sha256:[a-f0-9]{64}$'),
    document jsonb NOT NULL CHECK(jsonb_typeof(document)='object'),
    created_at timestamptz NOT NULL,
    UNIQUE(model_key, model_version, config_name)
);
CREATE INDEX ai_model_execution_created ON edgeai.ai_model_execution(created_at DESC, id);

CREATE FUNCTION edgeai.protect_ai_model_execution() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'AI model execution entries are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER ai_model_execution_immutable BEFORE UPDATE OR DELETE ON edgeai.ai_model_execution
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_ai_model_execution();
