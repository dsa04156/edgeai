-- Keep immutable app Device history for old runs; new VD sources use KubeEdge identity.
ALTER TABLE edgeai.vd_source_binding
    ALTER COLUMN device_id DROP NOT NULL,
    ALTER COLUMN device_profile_version_id DROP NOT NULL,
    ADD COLUMN managed_device_id varchar(253),
    ADD COLUMN device_uid uuid,
    ADD COLUMN device_model varchar(253),
    ADD CONSTRAINT vd_source_identity_kind CHECK (
        (managed_device_id IS NULL AND device_uid IS NULL AND device_model IS NULL
            AND device_id IS NOT NULL AND device_profile_version_id IS NOT NULL)
        OR (managed_device_id IS NOT NULL AND device_uid IS NOT NULL AND device_model IS NOT NULL
            AND device_id IS NULL AND device_profile_version_id IS NULL)
    );
CREATE INDEX vd_managed_source_active ON edgeai.vd_source_binding(managed_device_id,device_uid) WHERE closed_at IS NULL;

CREATE OR REPLACE FUNCTION edgeai.protect_virtual_device() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE spec jsonb;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD identity and history must be retained' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        SELECT p.spec INTO spec FROM edgeai.profile_version p WHERE p.id=NEW.profile_version_id AND p.kind='VD';
        IF coalesce(spec->>'apiVersion','') NOT IN ('edgeai.vd/v1','edgeai.vd/v2')
           OR lower(spec->>'serviceProfileVersionId') IS DISTINCT FROM NEW.service_profile_version_id::text
           OR NEW.state<>'REGISTERED' OR NEW.revision<>0 THEN
            RAISE EXCEPTION 'VD profile and service binding must match' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (NEW.id,NEW.vd_key,NEW.creation_digest,NEW.created_at)
            IS DISTINCT FROM (OLD.id,OLD.vd_key,OLD.creation_digest,OLD.created_at)
            OR OLD.state='RELEASED' OR NEW.revision<>OLD.revision+1 OR NEW.updated_at<OLD.updated_at THEN
            RAISE EXCEPTION 'VD identity, terminal state and revision are protected' USING ERRCODE='23514';
        END IF;
    END IF;
    SELECT p.spec INTO spec FROM edgeai.profile_version p WHERE p.id=NEW.profile_version_id AND p.kind='VD';
    IF lower(spec->>'serviceProfileVersionId') IS DISTINCT FROM NEW.service_profile_version_id::text THEN
        RAISE EXCEPTION 'VD service must match its template' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION edgeai.protect_vd_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE vd edgeai.virtual_device; device edgeai.device; requirement jsonb;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD source history must be retained' USING ERRCODE='23514';
    END IF;
    SELECT * INTO vd FROM edgeai.virtual_device WHERE id=NEW.vd_id FOR NO KEY UPDATE;
    IF TG_OP='UPDATE' THEN
        IF (to_jsonb(NEW)-'closed_revision'-'closed_at') IS DISTINCT FROM (to_jsonb(OLD)-'closed_revision'-'closed_at')
            OR OLD.closed_at IS NOT NULL OR NEW.closed_at IS NULL OR NEW.closed_revision IS DISTINCT FROM vd.revision THEN
            RAISE EXCEPTION 'VD source can only be closed once at the current revision' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.managed_device_id IS NOT NULL THEN
        SELECT spec->'sources'->NEW.source_key INTO requirement FROM edgeai.profile_version WHERE id=vd.profile_version_id;
        IF vd.state IS DISTINCT FROM 'REGISTERED' OR vd.revision IS DISTINCT FROM NEW.opened_revision
            OR NEW.closed_at IS NOT NULL OR requirement->>'deviceModel' IS DISTINCT FROM NEW.device_model THEN
            RAISE EXCEPTION 'VD sensor source must match its declared DeviceModel' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO device FROM edgeai.device WHERE id=NEW.device_id FOR UPDATE;
    SELECT spec->'sources'->NEW.source_key INTO requirement FROM edgeai.profile_version WHERE id=vd.profile_version_id;
    IF vd.state IS DISTINCT FROM 'REGISTERED' OR vd.revision IS DISTINCT FROM NEW.opened_revision
        OR NEW.closed_at IS NOT NULL OR device.state IS DISTINCT FROM 'ACTIVE'
        OR lower(requirement->>'deviceProfileVersionId') IS DISTINCT FROM NEW.device_profile_version_id::text
        OR NOT coalesce((requirement->'sourceModes') ? NEW.source_mode,false) THEN
        RAISE EXCEPTION 'VD source must match the declared profile and an active Device' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

