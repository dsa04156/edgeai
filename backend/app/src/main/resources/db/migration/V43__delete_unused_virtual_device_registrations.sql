-- Registration deletion is distinct from release. Execution history remains immutable.
CREATE FUNCTION edgeai.vd_registration_deletable(target uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM edgeai.virtual_device WHERE id=target AND state='RELEASED')
        AND NOT EXISTS (SELECT 1 FROM edgeai.vd_source_binding WHERE vd_id=target AND closed_at IS NULL)
        AND NOT EXISTS (SELECT 1 FROM edgeai.vd_runtime WHERE vd_id=target)
        AND NOT EXISTS (SELECT 1 FROM edgeai.vd_operation WHERE vd_id=target)
        AND NOT EXISTS (SELECT 1 FROM edgeai.workflow_run WHERE vd_id=target)
        AND NOT EXISTS (SELECT 1 FROM edgeai.task WHERE initial_vd_id=target)
        AND NOT EXISTS (SELECT 1 FROM edgeai.task_attempt WHERE vd_id=target)
        AND NOT EXISTS (SELECT 1 FROM edgeai.runtime_instance WHERE vd_id=target)
        AND NOT EXISTS (SELECT 1 FROM edgeai.task_offload_member WHERE target_vd_id=target)
$$;

CREATE FUNCTION edgeai.guard_unused_vd_registration_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;
BEGIN
    IF TG_TABLE_NAME='virtual_device' THEN target:=OLD.id; ELSE target:=OLD.vd_id; END IF;
    PERFORM id FROM edgeai.virtual_device WHERE id=target FOR UPDATE;
    IF NOT edgeai.vd_registration_deletable(target) THEN
        RAISE EXCEPTION 'VD must be released with no execution references before registration deletion' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
END;
$$;

-- Preserve existing INSERT/UPDATE and TRUNCATE guards verbatim; replace only DELETE behavior.
DROP TRIGGER protect_virtual_device_row ON edgeai.virtual_device;
CREATE TRIGGER protect_virtual_device_row BEFORE INSERT OR UPDATE ON edgeai.virtual_device
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_virtual_device();
CREATE TRIGGER delete_unused_virtual_device BEFORE DELETE ON edgeai.virtual_device
    FOR EACH ROW EXECUTE FUNCTION edgeai.guard_unused_vd_registration_delete();
DROP TRIGGER protect_vd_source_row ON edgeai.vd_source_binding;
CREATE TRIGGER protect_vd_source_row BEFORE INSERT OR UPDATE ON edgeai.vd_source_binding
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_vd_source();
CREATE TRIGGER delete_unused_vd_sources BEFORE DELETE ON edgeai.vd_source_binding
    FOR EACH ROW EXECUTE FUNCTION edgeai.guard_unused_vd_registration_delete();
