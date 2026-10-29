-- EDGE_TO_WORKER node-pressure decisions pin one explicitly selected worker (fixed NODE target, no exclusions).
ALTER TABLE edgeai.task_offload DROP CONSTRAINT offload_decision;
ALTER TABLE edgeai.task_offload ADD CONSTRAINT offload_decision CHECK(
    (trigger='MANUAL' AND cardinality(excluded_node_names)=0 AND decision IS NULL AND
        ((target_node_id IS NOT NULL AND remote_provider_key IS NULL AND remote_configuration_digest IS NULL AND remote_source_mode IS NULL) OR
         (target_node_id IS NULL AND edgeai.valid_remote_target(remote_provider_key,remote_configuration_digest,remote_source_mode)))) OR
    (trigger IN ('CPU','MEMORY','LATENCY') AND target_node_id IS NULL AND remote_provider_key IS NULL AND remote_configuration_digest IS NULL AND remote_source_mode IS NULL
     AND cardinality(excluded_node_names) BETWEEN 1 AND 16 AND decision IS NOT NULL AND jsonb_typeof(decision)='object') OR
    (trigger IN ('NODE_CPU','NODE_MEM','NODE_GPU') AND target_node_id IS NOT NULL AND remote_provider_key IS NULL AND remote_configuration_digest IS NULL AND remote_source_mode IS NULL
     AND cardinality(excluded_node_names)=0 AND decision IS NOT NULL AND jsonb_typeof(decision)='object'
     -- IS TRUE: a missing JSON path yields NULL, which a CHECK would otherwise accept.
     AND (jsonb_typeof(decision->'policy'->'nodePlacement')='object') IS TRUE
     AND (decision->'targetNode'->>'nodeId'=target_node_id::text) IS TRUE));
