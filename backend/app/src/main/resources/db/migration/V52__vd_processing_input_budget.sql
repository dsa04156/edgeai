-- Processing frames permit 1 MiB canonical JSON; reserve room for jsonb whitespace.
ALTER TABLE edgeai.vd_task_input DROP CONSTRAINT vd_task_input_document_check;
ALTER TABLE edgeai.vd_task_input ADD CONSTRAINT vd_task_input_document_check CHECK (
    COALESCE(jsonb_typeof(document)='object'
        AND octet_length(document::text)<=2097152
        AND document ?& ARRAY['apiVersion','vdId','vdRevision','capturedAt','sources']
        AND document->>'apiVersion'='edgeai.vd-input/v1'
        AND document->>'vdId'=vd_id::text
        AND jsonb_typeof(document->'vdRevision')='number'
        AND jsonb_typeof(document->'capturedAt')='string'
        AND jsonb_typeof(document->'sources')='object'
        AND (document->'sources'<>'{}'::jsonb OR (
            jsonb_typeof(document->'processing')='object'
            AND document->'processing'->>'apiVersion'='edgeai.processing/v1'
            AND jsonb_typeof(document->'processing'->'pipelines')='array'
            AND jsonb_array_length(document->'processing'->'pipelines')>0
        )), false)
);
