ALTER TABLE edgeai.vd_source_binding
    ADD COLUMN properties jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(properties)='array' AND jsonb_array_length(properties)<=64),
    ADD COLUMN max_age_seconds integer NOT NULL DEFAULT 30 CHECK (max_age_seconds BETWEEN 1 AND 300),
    ADD CONSTRAINT vd_legacy_measurements_empty CHECK (managed_device_id IS NOT NULL OR properties='[]'::jsonb);
