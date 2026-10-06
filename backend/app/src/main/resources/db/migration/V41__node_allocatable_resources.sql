ALTER TABLE edgeai.execution_node
    ADD COLUMN allocatable jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(allocatable) = 'object');
