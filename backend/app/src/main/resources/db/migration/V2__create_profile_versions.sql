CREATE TABLE edgeai.profile_version (
    id uuid PRIMARY KEY,
    kind varchar(7) NOT NULL CHECK (kind IN ('DEVICE', 'SERVICE', 'VD')),
    profile_key varchar(100) COLLATE "C" NOT NULL
        CHECK (profile_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    version varchar(32) COLLATE "C" NOT NULL
        CHECK (version ~ '^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$'),
    spec jsonb NOT NULL CHECK (jsonb_typeof(spec) = 'object' AND spec <> '{}'::jsonb),
    digest varchar(71) NOT NULL CHECK (digest ~ '^sha256:[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (kind, profile_key, version)
);

CREATE FUNCTION edgeai.reject_profile_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Published profile versions are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER immutable_profile_rows
    BEFORE UPDATE OR DELETE ON edgeai.profile_version
    FOR EACH ROW EXECUTE FUNCTION edgeai.reject_profile_mutation();
CREATE TRIGGER immutable_profile_truncate
    BEFORE TRUNCATE ON edgeai.profile_version
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.reject_profile_mutation();
