CREATE TABLE edgeai.vd_task_input (
    task_id uuid PRIMARY KEY REFERENCES edgeai.task(id),
    vd_id uuid NOT NULL REFERENCES edgeai.virtual_device(id),
    document jsonb NOT NULL CHECK (jsonb_typeof(document)='object' AND octet_length(document::text)<=65536
        AND document->>'apiVersion'='edgeai.vd-input/v1' AND document->>'vdId'=vd_id::text
        AND jsonb_typeof(document->'sources')='object')
);
CREATE FUNCTION edgeai.protect_vd_task_input() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'VD sensor task inputs are immutable' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM edgeai.task WHERE id=NEW.task_id AND initial_vd_id=NEW.vd_id) THEN
        RAISE EXCEPTION 'VD input must belong to its initially placed task' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_vd_task_input_row BEFORE INSERT OR UPDATE OR DELETE ON edgeai.vd_task_input
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_vd_task_input();
CREATE TRIGGER protect_vd_task_input_truncate BEFORE TRUNCATE ON edgeai.vd_task_input
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_vd_task_input();
