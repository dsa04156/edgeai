-- 3-3 resource requirement profiles: immutable revisions of one immutable SERVICE profile version.
-- Separate from the timing-based scheduling_profile (V67). Analyses keep the server-produced measurement/KRR
-- evidence that a revision may cite, so callers cannot label manual values as measured or recommended.
CREATE TABLE edgeai.resource_profile_analysis (
    id uuid PRIMARY KEY,
    service_profile_version_id uuid NOT NULL REFERENCES edgeai.profile_version(id),
    document jsonb NOT NULL CHECK(jsonb_typeof(document)='object'),
    created_at timestamptz NOT NULL
);
CREATE TABLE edgeai.resource_profile (
    id uuid PRIMARY KEY,
    service_profile_version_id uuid NOT NULL REFERENCES edgeai.profile_version(id),
    revision integer NOT NULL CHECK(revision BETWEEN 1 AND 1000000),
    idempotency_key uuid NOT NULL UNIQUE,
    analysis_id uuid REFERENCES edgeai.resource_profile_analysis(id),
    measurement_status varchar(16) NOT NULL CHECK(measurement_status IN ('MEASURED','PARTIAL','UNMEASURED')),
    digest varchar(71) NOT NULL CHECK(digest ~ '^sha256:[a-f0-9]{64}$'),
    document jsonb NOT NULL CHECK(jsonb_typeof(document)='object'),
    created_at timestamptz NOT NULL,
    UNIQUE(service_profile_version_id, revision)
);
CREATE INDEX resource_profile_analysis_service ON edgeai.resource_profile_analysis(service_profile_version_id, created_at);

CREATE FUNCTION edgeai.protect_resource_profile_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Resource profiles and analyses are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER resource_profile_immutable BEFORE UPDATE OR DELETE ON edgeai.resource_profile
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_resource_profile_record();
CREATE TRIGGER resource_profile_analysis_immutable BEFORE UPDATE OR DELETE ON edgeai.resource_profile_analysis
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_resource_profile_record();
