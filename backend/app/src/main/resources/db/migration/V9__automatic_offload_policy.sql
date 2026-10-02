ALTER TABLE edgeai.workflow_run ADD COLUMN offload_policy jsonb;
ALTER TABLE edgeai.workflow_run ADD CONSTRAINT run_offload_policy_object CHECK(offload_policy IS NULL OR jsonb_typeof(offload_policy)='object');
ALTER TABLE edgeai.task_attempt ADD COLUMN excluded_node_names text[] NOT NULL DEFAULT '{}';
ALTER TABLE edgeai.task_attempt ADD CONSTRAINT attempt_exclusions CHECK(cardinality(excluded_node_names)<=16 AND array_position(excluded_node_names,NULL) IS NULL AND (mode='AUTO' OR cardinality(excluded_node_names)=0));
ALTER TABLE edgeai.task_offload ALTER COLUMN target_node_id DROP NOT NULL;
ALTER TABLE edgeai.task_offload ADD COLUMN trigger varchar(8) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE edgeai.task_offload ADD COLUMN excluded_node_names text[] NOT NULL DEFAULT '{}';
ALTER TABLE edgeai.task_offload ADD COLUMN decision jsonb;
ALTER TABLE edgeai.task_offload ADD CONSTRAINT offload_decision CHECK(
    (trigger='MANUAL' AND target_node_id IS NOT NULL AND cardinality(excluded_node_names)=0 AND decision IS NULL) OR
    (trigger IN ('CPU','MEMORY','LATENCY') AND target_node_id IS NULL AND cardinality(excluded_node_names) BETWEEN 1 AND 16 AND decision IS NOT NULL AND jsonb_typeof(decision)='object'));
ALTER TABLE edgeai.task_offload ADD CONSTRAINT offload_exclusions CHECK(array_position(excluded_node_names,NULL) IS NULL);
