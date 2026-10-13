-- Processing definitions and generated rule templates are immutable control metadata.
CREATE TABLE edgeai.continuous_definition_version (
    vd_id uuid NOT NULL REFERENCES edgeai.virtual_device(id),
    revision bigint NOT NULL CHECK(revision>=0),
    profile_version_id uuid NOT NULL REFERENCES edgeai.profile_version(id),
    configuration jsonb NOT NULL,
    sources jsonb NOT NULL,
    rules jsonb NOT NULL,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY(vd_id,revision)
);
