-- A retired historical generation does not establish a new heartbeat window.
-- Its completion receipt records the last lease, not the original window or
-- intermediate actor observations. Never fabricate those missing observations.
CREATE OR REPLACE FUNCTION edgeai.initialize_route_heartbeat() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF edgeai.stream_recovery_row('generation',to_jsonb(NEW)) THEN
        RETURN NEW;
    END IF;
    INSERT INTO edgeai.route_heartbeat VALUES(NEW.id,
        (extract(epoch FROM NEW.lease_until-NEW.created_at)*1000000)::bigint,0,0,NEW.created_at,NEW.created_at);
    RETURN NEW;
END;
$$;
