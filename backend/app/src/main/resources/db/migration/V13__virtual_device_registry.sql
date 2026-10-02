CREATE TABLE edgeai.virtual_device (
    id uuid PRIMARY KEY,
    vd_key varchar(100) COLLATE "C" NOT NULL UNIQUE CHECK (vd_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    display_name varchar(128) NOT NULL CHECK (length(trim(display_name)) > 0),
    profile_version_id uuid NOT NULL, profile_kind varchar(7) NOT NULL DEFAULT 'VD' CHECK (profile_kind='VD'),
    service_profile_version_id uuid NOT NULL, service_kind varchar(7) NOT NULL DEFAULT 'SERVICE' CHECK (service_kind='SERVICE'),
    state varchar(16) NOT NULL DEFAULT 'REGISTERED' CHECK (state IN ('REGISTERED','RELEASED')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 9007199254740991),
    placement_mode varchar(8) NOT NULL CHECK (placement_mode IN ('AUTO','NODE')),
    node_id uuid REFERENCES edgeai.execution_node(id),
    creation_digest varchar(71) NOT NULL CHECK (creation_digest ~ '^sha256:[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((placement_mode='NODE') = (node_id IS NOT NULL)),
    FOREIGN KEY (profile_version_id,profile_kind) REFERENCES edgeai.profile_version(id,kind),
    FOREIGN KEY (service_profile_version_id,service_kind) REFERENCES edgeai.profile_version(id,kind)
);
ALTER TABLE edgeai.device ADD CONSTRAINT device_source_identity UNIQUE (id,profile_version_id,source_mode);
CREATE TABLE edgeai.vd_source_binding (
    id uuid PRIMARY KEY, vd_id uuid NOT NULL REFERENCES edgeai.virtual_device(id),
    source_key varchar(100) COLLATE "C" NOT NULL CHECK (source_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    device_id uuid NOT NULL, device_profile_version_id uuid NOT NULL, source_mode varchar(16) NOT NULL,
    opened_revision bigint NOT NULL CHECK (opened_revision BETWEEN 0 AND 9007199254740991),
    closed_revision bigint CHECK (closed_revision BETWEEN 1 AND 9007199254740991),
    opened_at timestamptz NOT NULL, closed_at timestamptz,
    CHECK ((closed_at IS NULL) = (closed_revision IS NULL)),
    CHECK (closed_at IS NULL OR (closed_at >= opened_at AND closed_revision > opened_revision)),
    FOREIGN KEY (device_id,device_profile_version_id,source_mode) REFERENCES edgeai.device(id,profile_version_id,source_mode)
);
CREATE UNIQUE INDEX vd_one_active_source ON edgeai.vd_source_binding(vd_id,source_key) WHERE closed_at IS NULL;
CREATE INDEX vd_source_device_active ON edgeai.vd_source_binding(device_id) WHERE closed_at IS NULL;
CREATE INDEX vd_source_history ON edgeai.vd_source_binding(vd_id,opened_revision DESC,source_key,id);

CREATE FUNCTION edgeai.protect_virtual_device() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE spec jsonb;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD identity and history must be retained' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        SELECT p.spec INTO spec FROM edgeai.profile_version p WHERE p.id=NEW.profile_version_id AND p.kind='VD';
        IF spec->>'apiVersion' IS DISTINCT FROM 'edgeai.vd/v1'
           OR lower(spec->>'serviceProfileVersionId') IS DISTINCT FROM NEW.service_profile_version_id::text
           OR NEW.state<>'REGISTERED' OR NEW.revision<>0 THEN
            RAISE EXCEPTION 'VD profile and service binding must match' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (NEW.id,NEW.vd_key,NEW.profile_version_id,NEW.service_profile_version_id,NEW.creation_digest,NEW.created_at)
            IS DISTINCT FROM (OLD.id,OLD.vd_key,OLD.profile_version_id,OLD.service_profile_version_id,OLD.creation_digest,OLD.created_at)
            OR OLD.state='RELEASED' OR NEW.revision<>OLD.revision+1 OR NEW.updated_at<OLD.updated_at THEN
            RAISE EXCEPTION 'VD identity, terminal state and revision are protected' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_virtual_device_row BEFORE INSERT OR UPDATE OR DELETE ON edgeai.virtual_device
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_virtual_device();
CREATE TRIGGER protect_virtual_device_truncate BEFORE TRUNCATE ON edgeai.virtual_device
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_virtual_device();

CREATE FUNCTION edgeai.protect_vd_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE vd edgeai.virtual_device; device edgeai.device; requirement jsonb;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD source history must be retained' USING ERRCODE='23514';
    END IF;
    SELECT * INTO vd FROM edgeai.virtual_device WHERE id=NEW.vd_id FOR UPDATE;
    IF TG_OP='UPDATE' THEN
        IF (to_jsonb(NEW)-'closed_revision'-'closed_at') IS DISTINCT FROM (to_jsonb(OLD)-'closed_revision'-'closed_at')
            OR OLD.closed_at IS NOT NULL OR NEW.closed_at IS NULL OR NEW.closed_revision IS DISTINCT FROM vd.revision THEN
            RAISE EXCEPTION 'VD source can only be closed once at the current revision' USING ERRCODE='23514';
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
CREATE TRIGGER protect_vd_source_row BEFORE INSERT OR UPDATE OR DELETE ON edgeai.vd_source_binding
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_vd_source();
CREATE TRIGGER protect_vd_source_truncate BEFORE TRUNCATE ON edgeai.vd_source_binding
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_vd_source();

-- Check the final transaction state: replacements may close then insert a required source.
CREATE FUNCTION edgeai.check_vd_source_set() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; vd edgeai.virtual_device; spec jsonb;
BEGIN
    IF TG_TABLE_NAME='virtual_device' THEN target:=NEW.id; ELSE target:=NEW.vd_id; END IF;
    SELECT * INTO vd FROM edgeai.virtual_device WHERE id=target;
    SELECT p.spec INTO spec FROM edgeai.profile_version p WHERE p.id=vd.profile_version_id;
    IF vd.state='RELEASED' THEN
        IF EXISTS (SELECT 1 FROM edgeai.vd_source_binding WHERE vd_id=target AND closed_at IS NULL) THEN
            RAISE EXCEPTION 'Released VD cannot have active sources' USING ERRCODE='23514';
        END IF;
    ELSIF EXISTS (
        SELECT 1 FROM jsonb_each(spec->'sources') s WHERE s.value->'required'='true'::jsonb
        AND NOT EXISTS (SELECT 1 FROM edgeai.vd_source_binding b WHERE b.vd_id=target AND b.source_key=s.key AND b.closed_at IS NULL)
    ) THEN
        RAISE EXCEPTION 'VD required source is missing' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER vd_complete_sources AFTER INSERT OR UPDATE ON edgeai.virtual_device
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION edgeai.check_vd_source_set();
CREATE CONSTRAINT TRIGGER vd_binding_complete_sources AFTER INSERT OR UPDATE ON edgeai.vd_source_binding
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION edgeai.check_vd_source_set();

CREATE FUNCTION edgeai.guard_device_vd_release() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state='RELEASED' AND EXISTS (
        SELECT 1 FROM edgeai.vd_source_binding WHERE device_id=NEW.id AND closed_at IS NULL
    ) THEN
        RAISE EXCEPTION 'Device is still an active VD source' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_device_vd_release BEFORE UPDATE OF state ON edgeai.device
    FOR EACH ROW EXECUTE FUNCTION edgeai.guard_device_vd_release();
