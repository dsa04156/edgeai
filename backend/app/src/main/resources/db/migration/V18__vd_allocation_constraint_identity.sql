-- PostgreSQL named the V16 cross-column sequence check *_check and the closure *_check1.
-- V17 has already been applied; retain it and correct the actual constraint identities here.
ALTER TABLE edgeai.vd_task_allocation ADD CONSTRAINT vd_allocation_completion_sequence
    CHECK(completion_sequence BETWEEN assigned_sequence AND 9007199254740991);
ALTER TABLE edgeai.vd_task_allocation DROP CONSTRAINT vd_task_allocation_check1;
