-- Bounded retry receipts live only for one minute; measurement history remains in the dedicated database.
CREATE TABLE edgeai.continuous_result_receipt (
    vd_id uuid NOT NULL REFERENCES edgeai.virtual_device(id), output_id varchar(63) NOT NULL,
    generation uuid NOT NULL, sequence bigint NOT NULL, received_at timestamptz NOT NULL,
    PRIMARY KEY(vd_id,output_id,generation,sequence)
);
CREATE INDEX continuous_receipt_expiry ON edgeai.continuous_result_receipt(received_at);
