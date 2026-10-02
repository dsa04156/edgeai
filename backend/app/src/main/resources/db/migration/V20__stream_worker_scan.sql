-- Open generations are durable commands and lease observations. Keyset scans do not
-- repeatedly select the first busy/failed page or scan retained closed history.
CREATE INDEX route_generation_broker_open_scan
    ON edgeai.route_generation (broker_digest, id) WHERE closed_at IS NULL;
