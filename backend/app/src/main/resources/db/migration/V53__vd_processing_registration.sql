-- The user's original sensor/processing request survives source and runtime changes.
CREATE TABLE edgeai.vd_processing_registration (
    vd_id uuid PRIMARY KEY REFERENCES edgeai.virtual_device(id) ON DELETE CASCADE,
    request_digest text NOT NULL CHECK (request_digest ~ '^sha256:[a-f0-9]{64}$')
);
CREATE FUNCTION edgeai.protect_vd_processing_registration() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Processing registration request identity is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER vd_processing_registration_immutable BEFORE UPDATE ON edgeai.vd_processing_registration
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_vd_processing_registration();
