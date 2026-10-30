# AI model profiling — final independent review, round 2

Reviewed 2026-10-30. **Standards: PASS, 0 remaining blocking findings. Spec: PASS, 0 remaining blocking findings.** All four Standards and five Spec findings from round one are resolved; no new reproducible blocking functional defect was found in this review.

## Scope and provenance

Baseline `b63ab43`; reviewed tracked changes and initially untracked feature files. During review the coordinator committed that snapshot as `ac8fd13` and merged documentation-only `becfac3`, producing `d12a4c6`; final comparison is `git diff b63ab43...d12a4c6`. The worktree was clean after that commit. Standards sources: supplied instructions, root/backend/dashboard `AGENTS.md`, `docs/contributing/development.md`, and the existing interface-design system. Spec sources: `/tmp/edgeai-model-profiling-contract.md`, `docs/guides/ai-model-profiling.md`, both round-one reports, and the two requested evidence documents. Applied the code-review skill's separate axes in this session, as this dispatch prohibits additional workers.

Implementation, tests, API/UI wiring, migration guards, and deployment configuration were inspected. This review concentrated on the round-one defects and their fixes; it does not claim exhaustive verification of every possible cluster configuration. No implementation files were modified, no workers spawned, and no deployment or production mutation performed.

## Standards

| Round-one finding | Disposition | Resolution and evidence |
|---|---|---|
| S1 P1 — release races outstanding creation | **Resolved — high confidence** | `backend/adapters/src/main/java/io/edgeai/adapters/kubernetes/KubernetesProfilingGateway.java:85` always creates suspended. `backend/app/src/main/java/io/edgeai/app/service/ModelProfilingReconciler.java:168` persists UID/STARTING through the conditional update before resume; `:175` rechecks durable STARTING and that UID. A stale PENDING worker can leave only an inert suspended Job after cancellation/cleanup. Passing integration test `createOrResumeDelayedPastCancelAndCleanupNeverRunsBesideTheNextRun` uses two reconciler instances, releases the slot and measures the next run before allowing the old create, then confirms no old Pod, no old PATCH, and orphan cleanup. |
| S2 P1 — noncanonical digest across JVMs | **Resolved — high confidence** | `KubernetesProfilingGateway.java:96` hashes recursively sorted maps (`:100`) while preserving array order. Fresh rerun `specDigestIsTheSameInFreshProcesses` compares four separately launched JVMs with the test JVM and passes. |
| S3 P1 — unbounded Kubernetes response bodies | **Resolved — high confidence** | `backend/adapters/src/main/java/io/edgeai/adapters/kubernetes/KubernetesRuntimeHttp.java:48` waits on the body-completing async future with a finite timeout and cancels on failure/interruption. All gateway methods use this transport, including list and DELETE; the existing 8 MiB response cap remains. `:64` uses shutdownNow and a two-second await. The fresh stalled-body test passes for ensure/observe/resume/delete/ownedRuns and close. |
| S4 P2 — partial actual spec comparison | **Resolved — high confidence** | `KubernetesProfilingGateway.java:136` compares the actual complete spec and controlled labels, not merely its digest. Recursive comparison rejects missing, changed, and extra fields; quantities normalize to base units. `:162` admits only explicit Kubernetes defaults with their expected values and UID/name-derived selectors/labels. Fresh tests accept the defaulted Kubernetes 1.31-shaped fixture and reject all 21 field mutations while retaining the original digest. |

No additional blocking documented-standard violation found. Heuristic-only refactors and formatting observations are excluded as requested.

### Fencing and deletion interleavings

`KubernetesProfilingGateway.java:230` reads the Job, verifies run ownership and persisted UID, rejects an already-deleting Job, then reads resourceVersion and checks admission before PATCH. The merge patch contains both UID and resourceVersion and never creates an absent Job. If cancellation occurs after admission but PATCH wins first, the old slot remains held until normal foreground cleanup removes its Job and Pods. If deletion wins first, its resourceVersion change or removal fences the stale PATCH; a replacement of the same name also fails the UID check. `:278` sends UID-preconditioned foreground DELETE and does not declare cleanup complete in a pass that listed a Job; a later pass requires both Job and Pod absence.

The fresh gateway tests cover denied admission, UID mismatch, deleting-object refusal, emitted resourceVersion/UID patch, 409 refusal, and 404 absence. The passing integration race test holds PATCH inside the fake API until after cancellation and cleanup, then verifies rejection and no recreated Job/Pod. These are client/fake-API tests, not a real API-server race exercise. Kubernetes documents suspended creation and resource-version concurrency/deletion semantics in [Jobs](https://kubernetes.io/docs/concepts/workloads/controllers/job/#suspending-a-job) and [API Concepts](https://kubernetes.io/docs/reference/using-api/api-concepts/); the interleaving assessment above is code reasoning against those semantics.

## Spec

| Round-one finding | Disposition | Resolution and evidence |
|---|---|---|
| P1 P2 — GET and octet-stream unusable from UI | **Resolved — high confidence** | `dashboard/kestra-native/model-profiling.ts:162` leaves GET content metadata/body absent; `:174` sends octet-stream only as bodyBase64. JSON/plain text retain body. Reviewed against actual `ModelProfilingInput.java:94`–`:102`, not only the handwritten frontend contract mirror. Fresh focused UI unit tests pass for all modes, stale draft fields, binary file encoding, and size validation. |
| P2 P2 — unsupported accelerator observations hidden | **Resolved — high confidence** | `dashboard/kestra-native/ModelProfilingPanel.vue:94` renders status and server reason independently of devices.length; `model-profiling.ts:270` preserves UNSUPPORTED and distinguishes NOT_REQUESTED/legacy unrecorded. The existing desktop/mobile Playwright screenshots visibly show the unsupported-exporter reason before confirmation despite an empty devices array; related browser assertions and fresh unit tests were inspected. |
| P3 P2 — process-dependent digest | **Resolved — high confidence** | Same implementation and separate-JVM evidence as S2. |
| P4 P2 — incomplete adoption equality | **Resolved — high confidence** | Same complete-spec/default-normalization comparison and mutation evidence as S4. |
| P5 P1 — cross-process late creation overlaps a new run | **Resolved — high confidence** | Same suspended-create/persist/conditional-resume protocol and two-reconciler race evidence as S1. Delayed creation may leave a suspended orphan temporarily; it cannot execute the old workload alongside the newly admitted run. |

No additional reproducible blocking spec finding found. READY remains tied to a confirmed successful-run profile, and failed remeasurement preserves an existing profile. The UI exposes observed versus allocated accelerator evidence and measurement limitations.

## Test evidence checked

- **Fresh targeted backend rerun: 14/14, zero failures/errors/skips.** `KubernetesProfilingGatewayTest` 9, `HttpInferenceLoadDriverTest` 5. XML is durable at `/tmp/edgeai-profiling-round2-tests/xml/`; timestamps 07:43:19Z and 07:43:27Z. Only these suites were rerun because their earlier XML had been replaced by the filtered contract run. Command: `rtk proxy backend/gradlew -p backend -I /tmp/edgeai-profiling-round2-tests.init.gradle :app:test --tests io.edgeai.adapters.kubernetes.KubernetesProfilingGatewayTest --tests io.edgeai.adapters.profiling.HttpInferenceLoadDriverTest --console=plain`. The first attempt failed in the reviewer-only report-location init script before tests; correcting that temporary script produced the passing run, with no source edits.
- Driver verification includes stalled headers and stalled bodies at concurrency four with a 60-second request timeout. `stopAndCloseAreBoundedWhileResponsesStall` passes for stop before and after the load deadline, uncounted interrupted exchanges, and bounded close. Actual implementation interrupts every worker regardless of deadline and shares one two-second wait budget (`HttpInferenceLoadDriver.java:149`); close uses shutdownNow (`:33`).
- **Existing current integration XML: 34/34, zero failures/errors/skips.** Profiling 6 (07:37:09Z), AiModel 7, OffloadLab 11, ResourceProfile 6, ResourceAnalysisDeadline 1, RegistryDeletion 3. Read the tests and XML; did not rerun these freshly passing suites.
- **Fresh focused frontend unit run: 10/10.** `rtk proxy node --experimental-strip-types --disable-warning=ExperimentalWarning --test dashboard/tests/model-profiling.unit.ts`.
- Read `docs/evidence/ai-model-profiling-20261030.md` and `docs/evidence/ai-model-design-20261030.md`: full backend unit 276 and frontend unit 162, frontend typecheck/lint/build, contract/docs checks, and Playwright desktop/mobile 40 are recorded there. Those broader totals are prior worker evidence, not claimed as rerun or independently reconstructed in this review. Inspected the unsupported-device screenshots and browser test source.

## Remaining release work

No unresolved round-one or new blocking functional findings. Actual production rollout and real inference measurement remain the coordinator's authorized release step, including real Job admission/defaulting and API-to-Pod connectivity. Their absence here is not a defect or a request for another independent review round. Strict spec comparison intentionally rejects unlisted admission mutations; the existing evidence records this deployment check.

**Final axis totals: Standards 0 open / 4 resolved; Spec 0 open / 5 resolved.**
