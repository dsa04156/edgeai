-- A later authenticated supervisor poll can prove an assignment response was never applied.
-- This is distinct from a process exit and must not invent an exit code.
ALTER TABLE edgeai.vd_task_allocation DROP CONSTRAINT vd_task_allocation_check;
ALTER TABLE edgeai.vd_task_allocation ADD CONSTRAINT vd_allocation_closure CHECK(
    (closed_at IS NULL AND close_reason IS NULL AND completion_sequence IS NULL AND exit_code IS NULL) OR
    (closed_at IS NOT NULL AND close_reason IS NOT NULL AND closed_at>=assigned_at AND
        ((close_reason='PROCESS_EXIT' AND completion_sequence IS NOT NULL AND exit_code IS NOT NULL) OR
         (close_reason='POD_GONE' AND completion_sequence IS NULL AND exit_code IS NULL) OR
         (close_reason='NOT_STARTED' AND completion_sequence IS NOT NULL AND completion_sequence>assigned_sequence AND exit_code IS NULL)))
);
