CREATE TABLE edgeai.runtime_telemetry (
    attempt_id uuid NOT NULL REFERENCES edgeai.task_attempt(id),
    sequence bigint NOT NULL CHECK(sequence BETWEEN 1 AND 9007199254740991),
    observed_at timestamptz NOT NULL, received_at timestamptz NOT NULL,
    interval_millis integer NOT NULL CHECK(interval_millis BETWEEN 200 AND 60000),
    cpu_usage_micros bigint CHECK(cpu_usage_micros BETWEEN 0 AND 9007199254740991),
    cpu_limit_millicores bigint CHECK(cpu_limit_millicores BETWEEN 1 AND 1000000000),
    memory_bytes bigint CHECK(memory_bytes BETWEEN 0 AND 9007199254740991),
    memory_limit_bytes bigint CHECK(memory_limit_bytes BETWEEN 1 AND 9007199254740991),
    latency_micros bigint CHECK(latency_micros BETWEEN 0 AND 600000000),
    latency_observed_at timestamptz,
    PRIMARY KEY(attempt_id,sequence),
    CHECK(cpu_usage_micros IS NOT NULL OR cpu_limit_millicores IS NULL),
    CHECK(memory_bytes IS NOT NULL OR memory_limit_bytes IS NULL),
    CHECK((latency_micros IS NULL)=(latency_observed_at IS NULL)),
    CHECK(cpu_usage_micros IS NOT NULL OR memory_bytes IS NOT NULL OR latency_micros IS NOT NULL)
);
