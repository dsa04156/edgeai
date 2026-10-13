CREATE TABLE edgeai_history.evaluation (
    evaluation_at timestamptz NOT NULL, vd_id uuid NOT NULL, generation uuid NOT NULL,
    sequence bigint NOT NULL, sources jsonb NOT NULL, expires_at timestamptz NOT NULL,
    PRIMARY KEY(evaluation_at,vd_id,generation,sequence)
) PARTITION BY RANGE(evaluation_at);
CREATE TABLE edgeai_history.result (
    evaluation_at timestamptz NOT NULL, vd_id uuid NOT NULL, output_id varchar(63) NOT NULL,
    generation uuid NOT NULL, sequence bigint NOT NULL,
    value jsonb NOT NULL, value_type varchar(16) NOT NULL CHECK(value_type IN ('number','boolean')),
    unit varchar(32) NOT NULL, expires_at timestamptz NOT NULL,
    PRIMARY KEY(evaluation_at,vd_id,output_id,generation,sequence)
) PARTITION BY RANGE(evaluation_at);
CREATE INDEX result_series ON edgeai_history.result(vd_id,output_id,evaluation_at,generation,sequence);
CREATE INDEX result_expiry ON edgeai_history.result(expires_at);
CREATE INDEX evaluation_expiry ON edgeai_history.evaluation(expires_at);
