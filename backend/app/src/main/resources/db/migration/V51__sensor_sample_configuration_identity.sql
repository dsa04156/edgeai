-- Preserve old observations, but never use legacy rows in a calibrated processing window.
ALTER TABLE edgeai.sensor_sample ADD COLUMN configuration_sha256 varchar(71) NOT NULL DEFAULT 'legacy';
ALTER TABLE edgeai.sensor_sample ADD CHECK (configuration_sha256='legacy' OR configuration_sha256 ~ '^sha256:[a-f0-9]{64}$');
ALTER TABLE edgeai.sensor_sample DROP CONSTRAINT sensor_sample_pkey;
ALTER TABLE edgeai.sensor_sample ADD PRIMARY KEY (device_uid,property_name,configuration_sha256,observed_at);
