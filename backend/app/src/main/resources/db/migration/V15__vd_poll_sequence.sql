ALTER TABLE edgeai.vd_runtime ADD CONSTRAINT vd_runtime_session_identity UNIQUE(id,session_id);

-- One last accepted request per runtime, with no credentials or workload payloads.
CREATE TABLE edgeai.vd_runtime_poll (
    runtime_id uuid PRIMARY KEY, session_id uuid NOT NULL,
    sequence bigint NOT NULL CHECK(sequence BETWEEN 0 AND 9007199254740991),
    request_digest varchar(71) NOT NULL CHECK(request_digest ~ '^sha256:[a-f0-9]{64}$'),
    command varchar(5) NOT NULL CHECK(command IN ('RUN','DRAIN','STOP')),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL CHECK(updated_at>=created_at),
    FOREIGN KEY(runtime_id,session_id) REFERENCES edgeai.vd_runtime(id,session_id)
);

CREATE FUNCTION edgeai.protect_vd_runtime_poll() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE runtime edgeai.vd_runtime;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'VD poll sequence must be retained' USING ERRCODE='23514';
    END IF;
    SELECT * INTO runtime FROM edgeai.vd_runtime WHERE id=NEW.runtime_id;
    IF runtime.id IS NULL OR runtime.session_id IS DISTINCT FROM NEW.session_id OR runtime.observed_state='TERMINATED'
        OR (NEW.command='RUN' AND runtime.desired_state<>'RUNNING')
        OR (NEW.command='DRAIN' AND runtime.desired_state<>'DRAINING')
        OR (NEW.command='STOP' AND runtime.desired_state<>'STOPPED')
        OR (NEW.command<>'STOP' AND runtime.lease_until<=NEW.updated_at) THEN
        RAISE EXCEPTION 'Poll authority must match the attested runtime' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.sequence<>0 THEN RAISE EXCEPTION 'First poll sequence must be zero' USING ERRCODE='23514'; END IF;
    ELSIF (NEW.runtime_id,NEW.session_id,NEW.created_at) IS DISTINCT FROM (OLD.runtime_id,OLD.session_id,OLD.created_at)
        OR NEW.sequence<OLD.sequence OR NEW.sequence>OLD.sequence+1 OR NEW.updated_at<OLD.updated_at
        OR (NEW.sequence=OLD.sequence AND NEW.request_digest<>OLD.request_digest)
        OR (OLD.command='DRAIN' AND NEW.command='RUN') OR (OLD.command='STOP' AND NEW.command<>'STOP') THEN
        RAISE EXCEPTION 'Poll identity, sequence and draining authority cannot regress' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER vd_runtime_poll_protected BEFORE INSERT OR UPDATE OR DELETE ON edgeai.vd_runtime_poll
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_vd_runtime_poll();
CREATE TRIGGER vd_runtime_poll_no_truncate BEFORE TRUNCATE ON edgeai.vd_runtime_poll
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_vd_runtime_poll();
