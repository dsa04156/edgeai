CREATE TABLE edgeai.sensor_sample (
    device_uid uuid NOT NULL,
    property_name varchar(63) NOT NULL,
    observed_at timestamptz NOT NULL,
    value_json jsonb NOT NULL,
    PRIMARY KEY (device_uid, property_name, observed_at),
    CHECK (jsonb_typeof(value_json) IN ('number','boolean','string'))
);
COMMENT ON TABLE edgeai.sensor_sample IS 'Append-only Mapper observations used to pin VD processing windows; repeated observations are deduplicated.';
