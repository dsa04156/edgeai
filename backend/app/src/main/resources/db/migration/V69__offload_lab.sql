-- returnToEdge: NODE_RETURN pins the task's ORIGINAL edge with the same evidence requirements as NODE_* (V62).
-- NODE_RETURN is 11 characters; widening a varchar keeps every stored value and needs no rewrite.
ALTER TABLE edgeai.task_offload ALTER COLUMN trigger TYPE varchar(16);
ALTER TABLE edgeai.task_offload DROP CONSTRAINT offload_decision;
ALTER TABLE edgeai.task_offload ADD CONSTRAINT offload_decision CHECK(
    (trigger='MANUAL' AND cardinality(excluded_node_names)=0 AND decision IS NULL AND
        ((target_node_id IS NOT NULL AND remote_provider_key IS NULL AND remote_configuration_digest IS NULL AND remote_source_mode IS NULL) OR
         (target_node_id IS NULL AND edgeai.valid_remote_target(remote_provider_key,remote_configuration_digest,remote_source_mode)))) OR
    (trigger IN ('CPU','MEMORY','LATENCY') AND target_node_id IS NULL AND remote_provider_key IS NULL AND remote_configuration_digest IS NULL AND remote_source_mode IS NULL
     AND cardinality(excluded_node_names) BETWEEN 1 AND 16 AND decision IS NOT NULL AND jsonb_typeof(decision)='object') OR
    (trigger IN ('NODE_CPU','NODE_MEM','NODE_GPU','NODE_RETURN') AND target_node_id IS NOT NULL AND remote_provider_key IS NULL AND remote_configuration_digest IS NULL AND remote_source_mode IS NULL
     AND cardinality(excluded_node_names)=0 AND decision IS NOT NULL AND jsonb_typeof(decision)='object'
     AND (jsonb_typeof(decision->'policy'->'nodePlacement')='object') IS TRUE
     AND (decision->'targetNode'->>'nodeId'=target_node_id::text) IS TRUE
     AND (trigger<>'NODE_RETURN' OR jsonb_typeof(decision->'policy'->'nodePlacement'->'returnToEdge')='object') IS TRUE));

-- Offload test console: one Run plus at most one owned, bounded CPU load Job generation per session. Not a scheduler.
CREATE TABLE edgeai.offload_lab_session(
    id uuid PRIMARY KEY,
    idempotency_key uuid NOT NULL UNIQUE,
    request_digest text NOT NULL,
    run_id uuid NOT NULL UNIQUE REFERENCES edgeai.workflow_run(id),
    edge_node_id uuid NOT NULL,
    edge_node_name text NOT NULL,
    state text NOT NULL CHECK(state IN ('ACTIVE','STOPPING','STOPPED')),
    load_generation integer NOT NULL DEFAULT 0 CHECK(load_generation BETWEEN 0 AND 1000),
    load_desired text NOT NULL DEFAULT 'NONE' CHECK(load_desired IN ('NONE','RUNNING','STOPPED')),
    load_key uuid,
    load_digest text,
    load_workers integer CHECK(load_workers BETWEEN 1 AND 16),
    load_seconds integer CHECK(load_seconds BETWEEN 30 AND 1800),
    load_started_at timestamptz,
    load_expires_at timestamptz,
    -- Set once the generation's Job exists, so a Job that finished and was garbage-collected is never recreated.
    load_job_uid uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    stopped_at timestamptz,
    CONSTRAINT offload_lab_load CHECK(
        (load_desired='NONE' AND load_generation=0 AND load_key IS NULL AND load_started_at IS NULL) OR
        (load_desired<>'NONE' AND load_generation>=1 AND load_key IS NOT NULL AND load_digest IS NOT NULL AND load_workers IS NOT NULL
         AND load_seconds IS NOT NULL AND load_started_at IS NOT NULL AND load_expires_at=load_started_at+make_interval(secs=>load_seconds))),
    CONSTRAINT offload_lab_stopped CHECK((state='STOPPED')=(stopped_at IS NOT NULL)),
    CONSTRAINT offload_lab_quiet CHECK(state='ACTIVE' OR load_desired<>'RUNNING')
);
-- A shared lab: at most one unfinished session, so a reload or second browser reattaches instead of starting another burner.
CREATE UNIQUE INDEX offload_lab_single_open ON edgeai.offload_lab_session((true)) WHERE state<>'STOPPED';
