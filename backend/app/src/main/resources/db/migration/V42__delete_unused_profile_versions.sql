-- Published content remains immutable; only unreferenced versions may be deleted.
CREATE OR REPLACE FUNCTION edgeai.reject_profile_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- Relational consumers are protected by foreign keys. VD specs also refer
        -- to SERVICE/DEVICE versions before a VD has been instantiated.
        IF EXISTS (
            SELECT 1 FROM edgeai.profile_version p
            WHERE p.id <> OLD.id AND p.kind = 'VD' AND (
                lower(p.spec->>'serviceProfileVersionId') = OLD.id::text OR EXISTS (
                    SELECT 1 FROM jsonb_each(CASE WHEN jsonb_typeof(p.spec->'sources') = 'object'
                        THEN p.spec->'sources' ELSE '{}'::jsonb END) source
                    WHERE lower(source.value->>'deviceProfileVersionId') = OLD.id::text
                )
            )
        ) THEN
            RAISE EXCEPTION 'Profile version is referenced by a VD profile' USING ERRCODE = '23503';
        END IF;
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Published profile content is immutable' USING ERRCODE = '23514';
END;
$$;
