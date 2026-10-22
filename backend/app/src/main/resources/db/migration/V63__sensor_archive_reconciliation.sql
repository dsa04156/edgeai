-- Bounded retry queue for hiding exact legacy duplicates of stored qualified originals; never part of the archive ACK.
CREATE TABLE edgeai.sensor_archive_reconciliation (
    device_uid uuid NOT NULL, property varchar(63) NOT NULL, series varchar(255) NOT NULL, sequence bigint NOT NULL,
    observed_at timestamptz NOT NULL, document text NOT NULL,
    attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL,
    PRIMARY KEY(device_uid,property,series,sequence)
);
CREATE INDEX sensor_archive_reconciliation_due ON edgeai.sensor_archive_reconciliation(next_attempt_at);
