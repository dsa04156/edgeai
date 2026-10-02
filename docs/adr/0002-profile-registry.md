# ADR 0002 — M1 immutable Profile registry

Accepted 2026-10-02. Sources: current Notion architecture §5, Profile APIs 1–3,
ERD PROFILE_VERSION; source timestamps unchanged from M0. Those drafts define
relationships and invariants, not full JSON schemas. These are local concrete
contract decisions, not claims about a supplied external implementation contract.

- Implement three Profile APIs under `/api/v1` for DEVICE, SERVICE, VD.
- Identity is `(kind, key, version)`, with UUID for future foreign keys. Key is a
  lowercase slug (100 chars), version is MAJOR.MINOR.PATCH (32 chars), no aliases,
  prerelease or automatic latest version. POST publishes immediately.
- `spec` is a nonempty JSON object. M1 validates JSON structure and limits, stores
  it unchanged in meaning, and does not certify device compatibility, runtime
  readiness, image availability, or service reference resolution. Kind-specific
  execution validation will be added when M2/M4/M6 consumers are implemented.
- Deterministic encoding v1: object keys sorted by Java UTF-16 natural order;
  array order retained; numbers parsed as arbitrary precision, trailing zeros
  removed, zero normalized to 0, plain decimal notation; JSON strings encoded
  by Jackson. No Unicode normalization. SHA-256 input UTF-8 is
  `edgeai-profile-v1\nKIND\n<canonical spec>`. This is not RFC 8785/JCS.
  Digest excludes key/version/timestamps. Duplicate JSON properties are rejected.
- Limit raw request to 64 KiB, nesting to 32, canonical spec to 64 KiB and numeric
  precision and absolute scale to 1000. Null characters/unpaired UTF-16
  surrogates are rejected (PostgreSQL JSONB cannot represent them).
- New identity returns 201+Location. Exact normalized content replay returns 200
  and original UUID/timestamp. Different content returns 409. Atomic INSERT ON
  CONFLICT DO NOTHING plus a following read provides concurrent idempotency.
- PostgreSQL unique constraint and UPDATE/DELETE/TRUNCATE triggers protect
  published rows against ordinary SQL mutation. The DB owner can bypass/remove
  triggers; this is not a privileged administrator security boundary.
- List is bounded (1–100), optional exact key filter, offset pagination; key and
  version lexical C order. It is not a snapshot across requests; concurrent
  inserts can shift pages. No semantic latest-version claims.
- Dashboard forwards only user-supplied Basic credentials to fixed API paths;
  credentials stay in component memory, never localStorage/server-injected.
  Existing CSRF protection remains: obtain token+session cookie, send both on
  publish. Loopback development authentication only; production identity/RBAC
  remains M9. Read/list/detail require authentication as well.
- No Kubernetes operation occurs in M1. Real PostgreSQL, HTTP security/contract,
  concurrency, DB immutability and desktop/mobile browser evidence close M1;
  platform LOCAL_VERIFIED/FULL_ACCEPTANCE still require future milestone evidence.

Dashboard uses [lossless-json](https://github.com/josdejong/lossless-json) for
spec submission and detail rendering so large integer/decimal values are not
rounded by native JavaScript JSON parsing. The dependency is exactly pinned in
package.json and the workspace lockfile.


Flyway history is explicitly pinned to `edgeai` (`spring.flyway.default-schema`).
Leaving it implicit caused a first-start/restart difference: creating a schema
named after the PostgreSQL user changes the effective default search_path. Fresh
CI reproduced this; an already initialized local database initially masked it.
No applied migration was edited and baseline/clean were not enabled.
M0's V1 is schema creation only and remains idempotent when history initializes
in the explicitly configured schema. A DB already containing Profile rows but
history only in public (the failed first M1 commit f3b2145) needs explicit history
reconciliation before reuse; the app intentionally does not auto-baseline it.
