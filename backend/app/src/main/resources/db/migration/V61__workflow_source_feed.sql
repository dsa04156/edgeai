-- Bounded ALL_OBSERVATIONS delivery queue for WORKFLOW_DATA inputs; durable measurement history stays in InfluxDB/history stores.
-- Appends for one source serialize on its feed row, so a committed head always covers a gap-free committed position prefix.
CREATE TABLE edgeai.workflow_source_feed (
    id uuid PRIMARY KEY,
    source_key varchar(200) COLLATE "C" NOT NULL UNIQUE,
    kind varchar(16) NOT NULL CHECK(kind IN ('SENSOR','CONTINUOUS_VD')),
    head bigint NOT NULL DEFAULT 0 CHECK(head BETWEEN 0 AND 9007199254740991),
    floor bigint NOT NULL DEFAULT 0 CHECK(floor>=0 AND floor<=head),
    trimmed_observed_at timestamptz,
    created_at timestamptz NOT NULL
);
CREATE TABLE edgeai.workflow_source_feed_entry (
    feed_id uuid NOT NULL REFERENCES edgeai.workflow_source_feed(id),
    position bigint NOT NULL CHECK(position>0),
    identity varchar(200) COLLATE "C" NOT NULL,
    observed_at timestamptz NOT NULL, accepted_at timestamptz NOT NULL,
    document jsonb NOT NULL CHECK(jsonb_typeof(document)='object'),
    PRIMARY KEY(feed_id,position), UNIQUE(feed_id,identity)
);
CREATE TABLE edgeai.workflow_source_subscription (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES edgeai.workflow_run(id),
    task_key varchar(100) COLLATE "C" NOT NULL,
    feed_id uuid NOT NULL REFERENCES edgeai.workflow_source_feed(id),
    start_position bigint NOT NULL CHECK(start_position>=0),
    acknowledged bigint NOT NULL,
    drain_position bigint,
    state varchar(16) NOT NULL CHECK(state IN ('ACTIVE','GAP','CLOSED')),
    gap_reason varchar(32) CHECK(gap_reason IN ('BACKLOG_EXCEEDED','DEDUP_WINDOW_EXCEEDED')),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE(run_id,task_key),
    CHECK(acknowledged>=start_position),
    CHECK(drain_position IS NULL OR drain_position>=start_position),
    CHECK((state='GAP')=(gap_reason IS NOT NULL))
);
CREATE INDEX workflow_source_subscription_feed ON edgeai.workflow_source_subscription(feed_id,state);
CREATE INDEX workflow_source_feed_entry_age ON edgeai.workflow_source_feed_entry(feed_id,accepted_at);
