-- CHECK must reject missing fields rather than letting SQL NULL pass.
-- jsonb's whitespace can be larger than the service's 64 KiB canonical input.
ALTER TABLE edgeai.vd_task_input DROP CONSTRAINT vd_task_input_check;
ALTER TABLE edgeai.vd_task_input ADD CONSTRAINT vd_task_input_document_check CHECK (
    COALESCE(jsonb_typeof(document)='object'
        AND octet_length(document::text)<=131072
        AND document ?& ARRAY['apiVersion','vdId','vdRevision','capturedAt','sources']
        AND document->>'apiVersion'='edgeai.vd-input/v1'
        AND document->>'vdId'=vd_id::text
        AND jsonb_typeof(document->'vdRevision')='number'
        AND jsonb_typeof(document->'capturedAt')='string'
        AND jsonb_typeof(document->'sources')='object'
        AND document->'sources'<>'{}'::jsonb, false)
);
