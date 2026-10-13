-- Temporary delivery queue only; durable measurement history belongs to InfluxDB.
CREATE TABLE edgeai.continuous_history_outbox (
    vd_id uuid NOT NULL REFERENCES edgeai.virtual_device(id), output_id varchar(63) NOT NULL,
    generation uuid NOT NULL, sequence bigint NOT NULL, evaluation_at timestamptz NOT NULL,
    retention_days integer NOT NULL CHECK(retention_days BETWEEN 1 AND 30),
    value_type varchar(16) NOT NULL, unit varchar(32) NOT NULL,
    value jsonb NOT NULL, sources jsonb NOT NULL, document jsonb NOT NULL, expires_at timestamptz NOT NULL,
    PRIMARY KEY(vd_id,output_id,generation,sequence)
);
CREATE INDEX continuous_history_delivery ON edgeai.continuous_history_outbox(evaluation_at,vd_id,output_id);
