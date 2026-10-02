ALTER TABLE edgeai.profile_version ADD CONSTRAINT profile_version_id_kind UNIQUE (id, kind);

CREATE TABLE edgeai.execution_node (
    id uuid PRIMARY KEY, name varchar(253) NOT NULL,
    architecture varchar(32) NOT NULL, operating_system varchar(32) NOT NULL,
    observed_status varchar(16) NOT NULL CHECK (observed_status IN ('READY','NOT_READY','UNKNOWN','REMOVED')),
    cpu varchar(64) NOT NULL, memory varchar(64) NOT NULL,
    labels jsonb NOT NULL CHECK (jsonb_typeof(labels) = 'object'), observed_at timestamptz NOT NULL
);
CREATE INDEX execution_node_name ON edgeai.execution_node(name, id);
CREATE TABLE edgeai.device (
    id uuid PRIMARY KEY, device_key varchar(100) COLLATE "C" NOT NULL UNIQUE
        CHECK (device_key ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    display_name varchar(128) NOT NULL CHECK (length(trim(display_name)) > 0),
    profile_version_id uuid NOT NULL, profile_kind varchar(7) NOT NULL DEFAULT 'DEVICE' CHECK (profile_kind='DEVICE'),
    source_mode varchar(16) NOT NULL CHECK (source_mode IN ('LIVE','REPLAY','SYNTHETIC')),
    state varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','RELEASED')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 9007199254740991),
    session_epoch bigint NOT NULL DEFAULT 0 CHECK (session_epoch BETWEEN 0 AND 9007199254740991),
    creation_digest varchar(71) NOT NULL CHECK (creation_digest ~ '^sha256:[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (profile_version_id,profile_kind) REFERENCES edgeai.profile_version(id,kind)
);
CREATE TABLE edgeai.device_attachment (
    id uuid PRIMARY KEY, device_id uuid NOT NULL REFERENCES edgeai.device(id),
    node_id uuid NOT NULL REFERENCES edgeai.execution_node(id), port varchar(128) NOT NULL CHECK(length(trim(port))>0),
    attached_at timestamptz NOT NULL, detached_at timestamptz,
    CHECK (detached_at IS NULL OR detached_at >= attached_at)
);
CREATE UNIQUE INDEX device_one_attachment ON edgeai.device_attachment(device_id) WHERE detached_at IS NULL;
CREATE INDEX device_attachment_history ON edgeai.device_attachment(device_id, attached_at DESC);
CREATE TABLE edgeai.device_session (
    id uuid PRIMARY KEY, device_id uuid NOT NULL REFERENCES edgeai.device(id), boot_id uuid NOT NULL,
    epoch bigint NOT NULL CHECK (epoch BETWEEN 1 AND 9007199254740991),
    last_sequence bigint NOT NULL DEFAULT -1 CHECK (last_sequence BETWEEN -1 AND 9007199254740991),
    opened_at timestamptz NOT NULL, closed_at timestamptz,
    UNIQUE(device_id,boot_id), UNIQUE(device_id,epoch), UNIQUE(id,device_id),
    CHECK (closed_at IS NULL OR closed_at >= opened_at)
);
CREATE UNIQUE INDEX device_one_session ON edgeai.device_session(device_id) WHERE closed_at IS NULL;
CREATE TABLE edgeai.device_observation (
    id uuid PRIMARY KEY, device_id uuid NOT NULL, session_id uuid NOT NULL,
    sequence bigint NOT NULL CHECK (sequence BETWEEN 0 AND 9007199254740991),
    observed_at timestamptz NOT NULL, received_at timestamptz NOT NULL,
    status varchar(8) NOT NULL CHECK (status IN ('ONLINE','OFFLINE')),
    attributes jsonb NOT NULL CHECK (jsonb_typeof(attributes)='object'),
    digest varchar(71) NOT NULL CHECK (digest ~ '^sha256:[a-f0-9]{64}$'),
    UNIQUE(session_id,sequence),
    FOREIGN KEY(session_id,device_id) REFERENCES edgeai.device_session(id,device_id)
);
CREATE INDEX device_observation_history ON edgeai.device_observation(device_id,received_at DESC);
