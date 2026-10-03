-- No payloads or credentials. Older generations keep their existing lease and
-- receive a conservative fixed 5s renewal window; their original TTL is unknown.
CREATE TABLE edgeai.route_heartbeat (
    generation_id uuid PRIMARY KEY REFERENCES edgeai.route_generation(id),
    window_micros bigint NOT NULL CHECK(window_micros BETWEEN 5000000 AND 120000000),
    producer_sequence bigint NOT NULL CHECK(producer_sequence BETWEEN 0 AND 9007199254740991),
    consumer_sequence bigint NOT NULL CHECK(consumer_sequence BETWEEN 0 AND 9007199254740991),
    producer_seen timestamptz NOT NULL,
    consumer_seen timestamptz NOT NULL
);
INSERT INTO edgeai.route_heartbeat
    SELECT id,5000000,0,0,created_at,created_at FROM edgeai.route_generation;

CREATE FUNCTION edgeai.protect_route_heartbeat() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE g edgeai.route_generation; observed timestamptz;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'Stream heartbeat history must be retained' USING ERRCODE='23514';
    END IF;
    SELECT * INTO g FROM edgeai.route_generation WHERE id=NEW.generation_id;
    PERFORM 1 FROM edgeai.data_route WHERE id=g.route_id FOR UPDATE;
    SELECT * INTO g FROM edgeai.route_generation WHERE id=NEW.generation_id;
    IF g.id IS NULL THEN RAISE EXCEPTION 'Stream generation required' USING ERRCODE='23514'; END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.window_micros<>(extract(epoch FROM g.lease_until-g.created_at)*1000000)::bigint
            OR NEW.producer_sequence<>0 OR NEW.consumer_sequence<>0
            OR NEW.producer_seen<>g.created_at OR NEW.consumer_seen<>g.created_at THEN
            RAISE EXCEPTION 'Heartbeat must begin with the original generation lease' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (NEW.generation_id,NEW.window_micros) IS DISTINCT FROM (OLD.generation_id,OLD.window_micros) THEN
            RAISE EXCEPTION 'Heartbeat identity and window are immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.producer_sequence=OLD.producer_sequence+1 AND NEW.producer_seen>=OLD.producer_seen
            AND (NEW.consumer_sequence,NEW.consumer_seen) IS NOT DISTINCT FROM (OLD.consumer_sequence,OLD.consumer_seen) THEN
            observed=NEW.producer_seen;
        ELSIF NEW.consumer_sequence=OLD.consumer_sequence+1 AND NEW.consumer_seen>=OLD.consumer_seen
            AND (NEW.producer_sequence,NEW.producer_seen) IS NOT DISTINCT FROM (OLD.producer_sequence,OLD.producer_seen) THEN
            observed=NEW.consumer_seen;
        ELSE
            RAISE EXCEPTION 'Heartbeat requires exactly one next actor observation' USING ERRCODE='23514';
        END IF;
        IF g.activated_at IS NULL OR g.fenced_at IS NOT NULL OR observed<g.updated_at OR observed>=g.lease_until THEN
            RAISE EXCEPTION 'Heartbeat requires a live active generation' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER route_heartbeat_protected BEFORE INSERT OR UPDATE OR DELETE ON edgeai.route_heartbeat
    FOR EACH ROW EXECUTE FUNCTION edgeai.protect_route_heartbeat();
CREATE TRIGGER route_heartbeat_no_truncate BEFORE TRUNCATE ON edgeai.route_heartbeat
    FOR EACH STATEMENT EXECUTE FUNCTION edgeai.protect_route_heartbeat();

-- An older application instance inserting generations during a rolling upgrade
-- also creates the fixed window and initial observations atomically.
CREATE FUNCTION edgeai.initialize_route_heartbeat() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO edgeai.route_heartbeat VALUES(NEW.id,
        (extract(epoch FROM NEW.lease_until-NEW.created_at)*1000000)::bigint,0,0,NEW.created_at,NEW.created_at);
    RETURN NEW;
END;
$$;
CREATE TRIGGER route_generation_heartbeat AFTER INSERT ON edgeai.route_generation
    FOR EACH ROW EXECUTE FUNCTION edgeai.initialize_route_heartbeat();
