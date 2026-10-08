-- Template replacement must validate the final active source set, including retained bindings.
CREATE OR REPLACE FUNCTION edgeai.check_vd_source_set() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; vd edgeai.virtual_device; spec jsonb;
BEGIN
    IF TG_TABLE_NAME='virtual_device' THEN target:=NEW.id; ELSE target:=NEW.vd_id; END IF;
    SELECT * INTO vd FROM edgeai.virtual_device WHERE id=target;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT p.spec INTO spec FROM edgeai.profile_version p WHERE p.id=vd.profile_version_id AND p.kind='VD';
    IF coalesce(spec->>'apiVersion','') NOT IN ('edgeai.vd/v1','edgeai.vd/v2') THEN
        RAISE EXCEPTION 'VD template version is unsupported' USING ERRCODE='23514';
    END IF;
    IF vd.state='RELEASED' THEN
        IF EXISTS (SELECT 1 FROM edgeai.vd_source_binding WHERE vd_id=target AND closed_at IS NULL) THEN
            RAISE EXCEPTION 'Released VD cannot have active sources' USING ERRCODE='23514';
        END IF;
        RETURN NULL;
    END IF;
    IF EXISTS (
        SELECT 1 FROM jsonb_each(spec->'sources') s WHERE s.value->'required'='true'::jsonb
        AND NOT EXISTS (SELECT 1 FROM edgeai.vd_source_binding b WHERE b.vd_id=target AND b.source_key=s.key AND b.closed_at IS NULL)
    ) THEN
        RAISE EXCEPTION 'VD required source is missing' USING ERRCODE='23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM edgeai.vd_source_binding b
        LEFT JOIN LATERAL jsonb_each(spec->'sources') s ON s.key=b.source_key
        WHERE b.vd_id=target AND b.closed_at IS NULL AND (
            s.key IS NULL
            OR (spec->>'apiVersion'='edgeai.vd/v2' AND
                (b.managed_device_id IS NULL OR s.value->>'deviceModel' IS DISTINCT FROM b.device_model))
            OR (spec->>'apiVersion'='edgeai.vd/v1' AND
                (b.device_id IS NULL OR lower(s.value->>'deviceProfileVersionId') IS DISTINCT FROM b.device_profile_version_id::text
                    OR NOT coalesce((s.value->'sourceModes') ? b.source_mode,false)))
        )
    ) THEN
        RAISE EXCEPTION 'VD active sources must match the current template' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
