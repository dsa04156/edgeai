"use client";
import { useState, type FormEvent } from "react";
import { parse, stringify } from "lossless-json";
import type { components, operations } from "../../lib/api-schema";
import { ConnectionPanel } from "../components/connection-panel";
import { RuntimeMeasurements } from "./runtime-measurements";
import { StreamInputFields, type StreamInput } from "./stream-inputs";
import { StreamRoutes } from "./stream-routes";
import { TaskExecutionFields, type TaskExecutions } from "./task-executions";
import { OffloadPolicyFields, OffloadPolicySummary, type AutomaticOffloadPolicy } from "./offload-policy";

type Schema = components["schemas"];
const stateNames: Record<string, string> = { PENDING: "실행 대기", WAITING: "입력 대기", READY: "실행 준비", RETRY_WAIT: "재시도 대기", OFFLOADING: "실행 위치 전환 중", DRAINING: "이전 실행 종료 중", STARTING: "새 실행 확인 중", QUEUED: "접수됨", DISPATCHING: "배치 중", RUNNING: "실행 중", SUCCEEDED: "성공", FAILED: "실패", CANCELLING: "종료 확인 중", CANCELLED: "취소됨", SKIPPED: "건너뜀", OFFLOADED: "전환됨" };
const terminal = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "SKIPPED"]);
function requestKey() {
  // getRandomValues also works on the existing private HTTP development ingress.
  const b = crypto.getRandomValues(new Uint8Array(16)); b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
  const h = Array.from(b, v => v.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
function objectJson(text: string): Record<string, unknown> {
  let value: unknown;
  try { value = parse(text); } catch { throw new Error("JSON 문법을 확인하세요."); }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("JSON 객체를 입력하세요.");
  return value as Record<string, unknown>;
}

export function WorkflowConsole() {
  const [auth, setAuth] = useState(""); const [csrf, setCsrf] = useState("");
  const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const [notice, setNotice] = useState("");
  const [workflows, setWorkflows] = useState<Schema["WorkflowPage"] | null>(null); const [workflowOffset, setWorkflowOffset] = useState(0);
  const [workflow, setWorkflow] = useState<Schema["WorkflowDetail"] | null>(null); const [versionOffset, setVersionOffset] = useState(0);
  const [version, setVersion] = useState<Schema["WorkflowVersion"] | null>(null); const [versionJson, setVersionJson] = useState("");
  const [profiles, setProfiles] = useState<Schema["ProfileVersion"][]>([]); const [nodes, setNodes] = useState<Schema["ExecutionNode"][]>([]);
  const [exampleProfile, setExampleProfile] = useState(""); const [dag, setDag] = useState('{"tasks": [], "dependencies": []}');
  const [runs, setRuns] = useState<Schema["RunPage"] | null>(null); const [runOffset, setRunOffset] = useState(0);
  const [run, setRun] = useState<Schema["RunDetail"] | null>(null); const [runJson, setRunJson] = useState("");
  const [task, setTask] = useState<Schema["TaskDetail"] | null>(null); const [key, setKey] = useState("");
  const [results, setResults] = useState<Schema["TaskResults"] | null>(null);
  const [mode, setMode] = useState("AUTO"); const [providerKey, setProviderKey] = useState("reference"); const [offloadMode, setOffloadMode] = useState("NODE"); const [parameters, setParameters] = useState("{}");
  const [vdId, setVdId] = useState("");
  const [streamInputs, setStreamInputs] = useState<StreamInput[]>([]);
  const [taskExecutions, setTaskExecutions] = useState<TaskExecutions>({});
  const externalTarget = ["REMOTE", "VD"].includes(mode) || Object.values(taskExecutions).some(value => ["REMOTE", "VD"].includes(value.mode));
  const streamExecution = streamInputs.length > 0 || !!version?.dag.dependencies.some(edge => edge.mode === "STREAM");
  const [retryAttempts, setRetryAttempts] = useState(1);
  const [automaticOffload, setAutomaticOffload] = useState<AutomaticOffloadPolicy | null>(null);
  const [retryBackoff, setRetryBackoff] = useState(5); const [retryWindow, setRetryWindow] = useState(600);
  const retryChoices = [
    ["WORKLOAD_FAILED", "작업 프로세스 실패"], ["TIMEOUT", "작업 시간 초과"], ["STORAGE_FAILED", "파일 저장소 오류"],
    ["RUNNER_FAILED", "실행기 오류"], ["DISPATCH_TIMEOUT", "배치 시간 초과"], ["RUNTIME_TIMEOUT", "실행 시간 초과"],
    ["RUNTIME_LOST", "실행 자원 유실"], ["JOB_FAILED", "Kubernetes Job 실패"],
  ] as const;
  const [retryOn, setRetryOn] = useState<string[]>(["STORAGE_FAILED", "RUNTIME_LOST"]);
  const [offload, setOffload] = useState<{ sourceAttemptId: string; key: string } | null>(null);
  const [cancel, setCancel] = useState<{ kind: "run" | "task"; id: string } | null>(null);

  async function api(path: string, init?: RequestInit, authorization = auth) {
    const response = await fetch(`/api/control-plane/${path}`, { ...init, cache: "no-store", headers: { Authorization: authorization, ...init?.headers } });
    if (!response.ok) {
      const value = await response.json().catch(() => ({}));
      throw new Error(response.status === 401 ? "계정 정보를 확인하고 다시 연결하세요." : response.status === 403 ? "연결이 만료되었습니다. 다시 연결하세요." : value.message || "요청을 처리하지 못했습니다.");
    }
    return response;
  }
  function post(path: string, value: object, idempotency?: string) {
    return api(path, { method: "POST", body: stringify(value), headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf, ...(idempotency ? { "Idempotency-Key": idempotency } : {}) } });
  }
  async function action(call: () => Promise<void>) { setBusy(true); setError(""); setNotice(""); try { await call(); } catch (e) { setError(e instanceof Error ? e.message : "요청을 처리하지 못했습니다."); } finally { setBusy(false); } }
  async function loadWorkflows(offset = workflowOffset, authorization = auth) { setWorkflows(await (await api(`workflows?limit=20&offset=${offset}`, undefined, authorization)).json()); setWorkflowOffset(offset); }
  async function loadRuns(offset = runOffset, authorization = auth) { setRuns(await (await api(`workflow-runs?limit=20&offset=${offset}`, undefined, authorization)).json()); setRunOffset(offset); }
  async function showWorkflow(id: string, offset = 0) {
    const value = await (await api(`workflows/${id}?limit=10&offset=${offset}`)).json();
    if (workflow?.workflow.id !== id) { setVersion(null); setVersionJson(""); setTaskExecutions({}); }
    setWorkflow(value); setVersionOffset(offset);
  }
  async function showVersion(workflowId: string, number: string) {
    const text = await (await api(`workflows/${workflowId}?version=${encodeURIComponent(number)}`)).text();
    const value = JSON.parse(text).versions[0]; setVersion(value);
    setVersionJson(stringify((parse(text) as { versions: { dag: unknown }[] }).versions[0].dag, null, 2) || "");
    setKey(requestKey()); setCancel(null); setTaskExecutions({});
  }
  async function showRun(id: string) {
    const text = await (await api(`workflow-runs/${id}`)).text(); setRun(JSON.parse(text)); setRunJson(stringify(parse(text), null, 2) || ""); setTask(null); setResults(null); setOffload(null); setCancel(null);
  }
  async function showTask(id: string) {
    setTask(null); setResults(null); setOffload(null);
    const [detail, result] = await Promise.all([api(`tasks/${id}`), api(`tasks/${id}/results`)]);
    setTask(await detail.json()); setResults(await result.json());
  }
  function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    void action(async () => {
      const authorization = `Basic ${btoa(String.fromCharCode(...new TextEncoder().encode(`${data.get("username")}:${data.get("password")}`)))}`;
      const token = await (await api("csrf", undefined, authorization)).json();
      await Promise.all([loadWorkflows(0, authorization), loadRuns(0, authorization)]);
      const profiles: operations["listProfiles"]["responses"][200]["content"]["application/json"] = await (await api("profiles/SERVICE?limit=100", undefined, authorization)).json();
      setProfiles(profiles.items); setExampleProfile(profiles.items[0]?.id || "");
      setNodes((await (await api("nodes?limit=100", undefined, authorization)).json()).items);
      setAuth(authorization); setCsrf(token.token); form.reset();
    });
  }
  function disconnect() {
    setVdId("");
    setTaskExecutions({});
    setAuth(""); setCsrf(""); setWorkflows(null); setWorkflow(null); setVersion(null); setVersionJson(""); setProfiles([]); setNodes([]);
    setRuns(null); setRun(null); setRunJson(""); setTask(null); setResults(null); setOffload(null); setCancel(null); setKey(""); setError(""); setNotice(""); setDag('{"tasks": [], "dependencies": []}'); setParameters("{}"); setRetryAttempts(1); setAutomaticOffload(null); setRetryBackoff(5); setRetryWindow(600); setRetryOn(["STORAGE_FAILED", "RUNTIME_LOST"]);
  }
  async function confirmCancellation() {
    if (!cancel) return;
    await post(cancel.kind === "run" ? `workflow-runs/${cancel.id}/cancel` : `tasks/${cancel.id}/cancel`, {});
    if (run) await showRun(run.run.id); await loadRuns(); setNotice("취소 요청을 반영했습니다. 작업별 상태를 확인하세요.");
  }

  return <>
    <ConnectionPanel connected={!!auth} busy={busy} onConnect={login} onDisconnect={disconnect} />
    <div aria-live="polite" aria-atomic="true">{notice && <p className="notice">{notice}</p>}</div>
    {error && <p className="error" role="alert">{error}</p>}
    {auth && <>
      <section aria-labelledby="workflows-title" aria-busy={busy}>
        <div className="toolbar"><h2 id="workflows-title">등록된 워크플로</h2><button disabled={busy} onClick={() => void action(() => loadWorkflows())}>워크플로 새로고침</button></div>
        {busy && <p role="status">처리 중…</p>}
        {workflows?.items.length ? <div className="table-scroll"><table><caption className="sr-only">워크플로 목록</caption><thead><tr><th>워크플로</th><th>생성 시각</th></tr></thead><tbody>{workflows.items.map(w => <tr key={w.id}><td><button className="version-link" disabled={busy} onClick={() => void action(() => showWorkflow(w.id))}>{w.displayName}<span>{w.key}</span></button></td><td>{new Date(w.createdAt).toLocaleString()}</td></tr>)}</tbody></table></div> : <p className="empty">아직 등록된 워크플로가 없습니다.</p>}
        <div className="pagination"><button disabled={busy || workflowOffset === 0} onClick={() => void action(() => loadWorkflows(workflowOffset - 20))}>이전 워크플로</button><span>{workflowOffset / 20 + 1} 페이지</span><button disabled={busy || workflows?.nextOffset == null} onClick={() => void action(() => loadWorkflows(workflows!.nextOffset!))}>다음 워크플로</button></div>
      </section>
      <section className="panel" aria-labelledby="workflow-create-title"><h2 id="workflow-create-title">새 워크플로</h2>
        <form onSubmit={event => { event.preventDefault(); const form = event.currentTarget; const data = new FormData(form); void action(async () => {
          const response = await post("workflows", Object.fromEntries(data)); const value = await response.json();
          await showWorkflow(value.id); await loadWorkflows(0); setNotice(response.status === 201 ? "워크플로를 생성했습니다. DAG 버전을 발행하세요." : "동일한 워크플로가 이미 있습니다."); form.reset();
        }); }}><fieldset disabled={busy} className="publish-fields"><div className="form-row"><label>워크플로 키<input name="key" required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" placeholder="factory-inspection" /></label><label>워크플로 이름<input name="displayName" required maxLength={128} placeholder="공장 검사" /></label></div><button className="primary">워크플로 생성</button></fieldset></form>
      </section>
      {workflow && <section className="panel" aria-labelledby="workflow-detail-title"><h2 id="workflow-detail-title">{workflow.workflow.displayName}</h2><p className="digest mono">Workflow ID {workflow.workflow.id}</p>
        <h3>발행된 DAG 버전</h3>
        {workflow.versions.length ? <ul className="history-list">{workflow.versions.map(v => <li key={v.id}><button className="version-link" disabled={busy} onClick={() => void action(() => showVersion(v.workflowId, v.version))}>{v.version}</button> · {new Date(v.createdAt).toLocaleString()}</li>)}</ul> : <p className="muted">발행된 버전이 없습니다.</p>}
        <div className="pagination"><button disabled={busy || versionOffset === 0} onClick={() => void action(() => showWorkflow(workflow.workflow.id, versionOffset - 10))}>이전 버전</button><span>{versionOffset / 10 + 1} 페이지</span><button disabled={busy || workflow.nextOffset == null} onClick={() => void action(() => showWorkflow(workflow.workflow.id, workflow.nextOffset!))}>다음 버전</button></div>
        <h3>새 DAG 버전 발행</h3>
        <div className="toolbar filter-form"><label>예제용 SERVICE Profile ID<input list="workflow-service-profiles" value={exampleProfile} onChange={e => setExampleProfile(e.target.value)} /></label><button disabled={busy || !exampleProfile} onClick={() => setDag(JSON.stringify({ tasks: [{ key: "source", serviceProfileVersionId: exampleProfile, parameters: {} }, { key: "process", serviceProfileVersionId: exampleProfile, parameters: {} }], dependencies: [{ fromTask: "source", toTask: "process", fromPort: "output", toPort: "input", mode: "BATCH" }] }, null, 2))}>예제 DAG 채우기</button></div>
        <datalist id="workflow-service-profiles">{profiles.map(p => <option key={p.id} value={p.id}>{p.key} · {p.version}</option>)}</datalist>
        <form onSubmit={event => { event.preventDefault(); const number = String(new FormData(event.currentTarget).get("version")); void action(async () => {
          const document = objectJson(dag);
          if (Object.keys(document).sort().join(",") !== "dependencies,tasks") throw new Error("DAG에는 tasks와 dependencies만 입력하세요.");
          const response = await post(`workflows/${workflow.workflow.id}/versions`, { version: number, ...document }); const v = await response.json();
          await showWorkflow(workflow.workflow.id); await showVersion(workflow.workflow.id, v.version); setNotice(response.status === 201 ? "DAG 버전을 발행했습니다." : "같은 내용의 DAG 버전이 이미 있습니다.");
        }); }}><fieldset disabled={busy} className="publish-fields"><label>DAG 버전<input name="version" defaultValue="1.0.0" required maxLength={32} /></label><label>DAG JSON<textarea rows={14} required value={dag} onChange={e => setDag(e.target.value)} spellCheck={false} /></label><p className="hint">SERVICE Profile 버전과 작업 간 연결을 입력합니다. 순환·중복 입력 포트는 허용하지 않으며 발행 후 변경은 새 버전으로 남깁니다.</p><button className="primary">DAG 발행</button></fieldset></form>
      </section>}
      {version && <section className="panel" aria-labelledby="selected-version-title"><h2 id="selected-version-title">선택한 DAG · {version.version}</h2><p className="digest mono">버전 ID {version.id}</p><p className="digest mono">{version.digest}</p><pre aria-label="발행된 DAG JSON">{versionJson}</pre>
        <h3>실행 요청</h3><form onSubmit={event => { event.preventDefault(); const nodeId = String(new FormData(event.currentTarget).get("nodeId")); void action(async () => {
          if (streamExecution && externalTarget) throw new Error("STREAM 실행은 AUTO/NODE 작업 배치만 지원합니다.");
          if (retryAttempts > 1 && retryOn.length === 0) throw new Error("재시도할 오류를 한 개 이상 선택하세요.");
          if (automaticOffload && [automaticOffload.cpuPercent, automaticOffload.memoryPercent, automaticOffload.latencyMicros].every(v => v == null)) throw new Error("자동 전환 기준을 한 개 이상 입력하세요.");
          const response = await post("workflow-runs", { workflowVersionId: version.id, execution: mode === "AUTO" ? { mode } : mode === "REMOTE" ? { mode, providerKey } : mode === "VD" ? { mode, vdId } : { mode, nodeId }, parameters: objectJson(parameters),
            ...(streamInputs.length ? { streamInputs } : {}),
            ...(Object.keys(taskExecutions).length ? { taskExecutions } : {}),
            ...(automaticOffload && !externalTarget ? { offload: automaticOffload } : {}),
            ...(retryAttempts > 1 ? { retry: { maxAttempts: retryAttempts, backoffSeconds: retryBackoff, maxElapsedSeconds: retryWindow, retryOn } } : {}) }, key);
          const value = await response.json(); await showRun(value.id); await loadRuns(0); setNotice(response.status === 201 ? (value.state === "PENDING" ? "실행 요청을 저장했습니다. 작업은 실행 대기 상태입니다." : `실행 요청을 저장했습니다. 현재 상태는 ${stateNames[value.state]}입니다.`) : "동일한 실행 요청을 조회했습니다. 새 실행은 만들지 않았습니다.");
        }); }}><fieldset disabled={busy} className="publish-fields">
          <label>실행 위치 정책<select value={mode} onChange={e => { setMode(e.target.value); if (["REMOTE", "VD"].includes(e.target.value)) setAutomaticOffload(null); }}><option value="AUTO">자동 선택 (AUTO)</option><option value="NODE">노드 지정 (NODE)</option><option value="REMOTE">원격 제공자 (REMOTE)</option><option value="VD">가상 장치 (VD)</option></select></label>
          {mode === "NODE" && <label>실행 노드 ID<input name="nodeId" list="workflow-execution-nodes" required maxLength={36} placeholder="관측된 Node UUID" /></label>}
          {mode === "REMOTE" && <><label>Remote 제공자 key<input required maxLength={63} pattern="[a-z][a-z0-9]*(-[a-z0-9]+)*" value={providerKey} onChange={e => setProviderKey(e.target.value)} /></label><p className="hint">서버에 설정된 제공자를 사용합니다. 참조 제공자의 합성 계산은 실제 장비·모델 검증과 구분합니다.</p></>}
          {mode === "VD" && <><label>실행할 가상 장치 ID<input required maxLength={36} value={vdId} onChange={e => setVdId(e.target.value)} placeholder="Ready인 VD UUID" /></label><p className="hint"><a href="/virtual-devices">가상 장치</a>에서 준비 상태와 ID를 확인하세요. 이 VD에 배치하는 작업은 같은 SERVICE 버전을 사용하며 실행 자리가 비면 시작합니다. 공유 자원 측정으로는 작업별 자동 전환을 설정할 수 없습니다.</p></>}
          <datalist id="workflow-execution-nodes">{nodes.map(n => <option key={n.id} value={n.id}>{n.name} · {n.architecture}</option>)}</datalist>
          {version.dag.tasks.length > 0 && <TaskExecutionFields tasks={version.dag.tasks} value={taskExecutions} stream={streamExecution} onChange={value => { setTaskExecutions(value); if (Object.values(value).some(target => ["VD", "REMOTE"].includes(target.mode))) setAutomaticOffload(null); }} />}
          <label>실행 매개변수 JSON<textarea rows={4} value={parameters} onChange={e => setParameters(e.target.value)} spellCheck={false} /></label>
          <StreamInputFields value={streamInputs} onChange={setStreamInputs} />
          <label>최대 실행 횟수<input type="number" min={1} max={8} required value={retryAttempts} onChange={e => setRetryAttempts(Number(e.target.value))} /></label>
          <p className="hint">최초 실행을 포함합니다. 1회이면 자동 재시도하지 않습니다.</p>
          {retryAttempts > 1 && <>
            <div className="form-row"><label>재시도 대기 시간(초)<input type="number" min={1} max={300} required value={retryBackoff} onChange={e => setRetryBackoff(Number(e.target.value))} /></label><label>재시도 허용 기간(초)<input type="number" min={1} max={86400} required value={retryWindow} onChange={e => setRetryWindow(Number(e.target.value))} /></label></div>
            <fieldset className="retry-errors"><legend>재시도할 오류</legend>{retryChoices.map(([code, label]) => <label key={code}><input type="checkbox" checked={retryOn.includes(code)} onChange={e => setRetryOn(e.target.checked ? [...retryOn, code] : retryOn.filter(item => item !== code))} />{label}</label>)}</fieldset>
            <p className="hint">각 작업의 최초 실행 시도부터 허용 기간을 계산합니다. 이전 실행의 종료를 확인한 뒤 다시 실행하며, 입력·출력 오류와 취소는 재시도하지 않습니다.</p>
          </>}
          {!externalTarget && <OffloadPolicyFields value={automaticOffload} onChange={setAutomaticOffload} />}
          <label>실행 요청 키<input value={key} readOnly className="mono" /></label><p className="hint">같은 키·내용을 다시 보내면 기존 실행을 반환합니다. 다른 실행을 만들 때 새 키를 발급하세요.</p>
          <div className="toolbar"><button type="button" onClick={() => setKey(requestKey())}>새 실행 키 만들기</button><button className="primary">실행 요청 저장</button></div>
        </fieldset></form>
      </section>}
      <section aria-labelledby="runs-title"><div className="toolbar"><h2 id="runs-title">실행 이력</h2><button disabled={busy} onClick={() => void action(async () => { await loadRuns(); if (run) await showRun(run.run.id); })}>실행 새로고침</button></div>
        {runs?.items.length ? <div className="table-scroll"><table><caption className="sr-only">실행 요청 목록</caption><thead><tr><th>Run ID</th><th>상태 / 정책</th><th>생성 시각</th></tr></thead><tbody>{runs.items.map(r => <tr key={r.id}><td><button className="version-link mono" disabled={busy} onClick={() => void action(() => showRun(r.id))}>{r.id}</button></td><td>{stateNames[r.state]}<span className="block muted">{r.mode}{r.remoteTarget ? ` · ${r.remoteTarget.providerKey} · ${r.remoteTarget.sourceMode}` : ""}</span></td><td>{new Date(r.createdAt).toLocaleString()}</td></tr>)}</tbody></table></div> : <p className="empty">아직 실행 요청이 없습니다. 발행한 DAG 버전을 선택해 요청을 만드세요.</p>}
        <div className="pagination"><button disabled={busy || runOffset === 0} onClick={() => void action(() => loadRuns(runOffset - 20))}>이전 실행</button><span>{runOffset / 20 + 1} 페이지</span><button disabled={busy || runs?.nextOffset == null} onClick={() => void action(() => loadRuns(runs!.nextOffset!))}>다음 실행</button></div>
      </section>
      {run && <section className="panel" aria-labelledby="run-detail-title"><div className="toolbar"><h2 id="run-detail-title">선택한 실행</h2><span className="stage">{stateNames[run.run.state]}</span></div><p className="digest mono">Run ID {run.run.id}</p>
        {run.run.vdId && <p className="digest mono">실행 대상 VD {run.run.vdId}</p>}
        <p className="hint">{run.run.retry && run.run.retry.maxAttempts > 1 ? `작업별 최대 ${run.run.retry.maxAttempts}회 · 실패 후 ${run.run.retry.backoffSeconds}초 대기 · 최초 시도부터 ${run.run.retry.maxElapsedSeconds}초 동안 재시도 가능` : "자동 재시도 없음 · 작업별 최초 1회"}</p>
        <OffloadPolicySummary value={run.run.offload} />
        <StreamRoutes key={run.run.id} tasks={run.tasks} disabled={busy} fetchPage={async offset => (await api(`workflow-runs/${run.run.id}/streams?limit=20&offset=${offset}`)).json()} />
        <div className="table-scroll"><table><caption className="sr-only">실행에 속한 작업</caption><thead><tr><th>작업</th><th>상태</th><th>작업 제어</th></tr></thead><tbody>{run.tasks.map(t => <tr key={t.id}><td><button className="version-link" disabled={busy} onClick={() => void action(() => showTask(t.id))}>{t.key}</button><span className="block muted digest">최초 배치 · {t.initialMode}{t.initialNodeId ? ` · ${nodes.find(n => n.id === t.initialNodeId)?.name || t.initialNodeId}` : t.initialVdId ? ` · ${t.initialVdId}` : t.initialRemoteTarget ? ` · ${t.initialRemoteTarget.providerKey}` : ""}</span></td><td>{stateNames[t.state]}{t.cancellationReason === "UPSTREAM_CANCELLED" && <span className="block muted">선행 작업 취소</span>}</td><td>{!terminal.has(t.state) && <button disabled={busy} onClick={() => setCancel({ kind: "task", id: t.id })} aria-label={`${t.key} 작업 취소`}>작업 취소</button>}</td></tr>)}</tbody></table></div>
        {task && <div><h3>실행 시도 · {task.task.key}</h3>{task.attempts.length ? <ul className="history-list">{task.attempts.map(a => <li key={a.id}>Attempt #{a.number} · epoch {a.epoch} · {stateNames[a.state]}{a.cause && <span className="block muted">{({ INITIAL: "최초 실행", RETRY: "재시도", OFFLOAD: "위치 전환" })[a.cause]} · {a.mode}{a.remoteTarget ? ` · ${a.remoteTarget.providerKey} · ${a.remoteTarget.sourceMode}` : ""}{a.nodeId ? ` · ${nodes.find(n => n.id === a.nodeId)?.name || a.nodeId}` : ""}</span>}<span className="block mono digest">{a.id}</span></li>)}</ul> : <p className="muted">{task.task.state === "WAITING" ? "선행 작업을 기다리는 중이며 아직 실행 시도가 없습니다." : "생성된 실행 시도가 없습니다."}</p>}</div>}
        {task && (task.attempts[0]?.mode === "REMOTE"
          ? <p className="hint">현재 Remote 실행은 자원·지연 측정을 지원하지 않습니다.</p>
          : <RuntimeMeasurements sample={task.telemetry} active={task.attempts[0]?.state === "RUNNING"} />)}
        {task?.attempts[0]?.vdId && <p className="hint">현재 작업의 VD: {task.attempts[0].vdId}. CPU·메모리는 VD 컨테이너 전체의 공유 측정값입니다.</p>}
        {task && <div role="region" aria-label="실행 위치 전환">
          <h3>실행 위치 전환</h3>
          <p className="hint">RESTART 작업은 같은 입력으로 선택한 노드 또는 Remote에서 처음부터 실행합니다. 체크포인트가 확정된 STREAM은 노드로 전환할 수 있습니다. 연결된 작업들도 함께 종료·재개하며 처리 상태를 이어받습니다. 전환 성공은 새 실행 시작을 뜻합니다.</p>
          {task.task.state === "RUNNING" && task.attempts.some(a => a.state === "RUNNING") && !offload && <button disabled={busy} onClick={() => setOffload({ sourceAttemptId: task.attempts.find(a => a.state === "RUNNING")!.id, key: requestKey() })}>실행 위치 전환</button>}
          {offload && <form onSubmit={event => { event.preventDefault(); const data = new FormData(event.currentTarget); const taskId = task.task.id; void action(async () => {
            await post(`tasks/${taskId}/offload`, { sourceAttemptId: offload.sourceAttemptId, ...(offloadMode === "REMOTE" ? { targetProviderKey: String(data.get("targetProviderKey")) } : { targetNodeId: String(data.get("targetNodeId")) }), drainTimeoutSeconds: Number(data.get("drainTimeoutSeconds")), startTimeoutSeconds: Number(data.get("startTimeoutSeconds")) }, offload.key);
            await showRun(run.run.id); await showTask(taskId); await loadRuns(); setNotice("전환 요청을 접수했습니다. 이전 실행 종료와 새 실행 시작 상태를 확인하세요.");
          }); }}><fieldset disabled={busy} className="publish-fields">
            <label>전환 대상 종류<select value={offloadMode} onChange={e => setOffloadMode(e.target.value)}><option value="NODE">Kubernetes 노드</option><option value="REMOTE">Remote 제공자</option></select></label>
            {offloadMode === "REMOTE" ? <label>전환할 Remote 제공자 key<input name="targetProviderKey" defaultValue="reference" required maxLength={63} pattern="[a-z][a-z0-9]*(-[a-z0-9]+)*" /></label> : <label>전환할 노드<input name="targetNodeId" list="offload-target-nodes" required maxLength={36} placeholder="다른 READY 노드 UUID" /></label>}
            <datalist id="offload-target-nodes">{nodes.map(n => <option key={n.id} value={n.id}>{n.name} · {n.status}</option>)}</datalist>
            <div className="form-row"><label>이전 실행 종료 제한(초)<input name="drainTimeoutSeconds" type="number" min={1} max={600} defaultValue={60} required /></label><label>새 실행 시작 제한(초)<input name="startTimeoutSeconds" type="number" min={1} max={600} defaultValue={120} required /></label></div>
            <div className="toolbar"><button className="primary">전환 요청</button><button type="button" onClick={() => setOffload(null)}>전환 닫기</button></div>
          </fieldset></form>}
          {task.offloads?.length ? <ul className="history-list">{task.offloads.map(o => <li key={o.id}>
            <strong>{o.state === "SUCCEEDED" ? "전환 성공 · 새 실행 시작됨" : stateNames[o.state]}</strong> · {o.remoteTarget ? `Remote ${o.remoteTarget.providerKey} · ${o.remoteTarget.sourceMode}` : o.targetNodeId ? nodes.find(n => n.id === o.targetNodeId)?.name || o.targetNodeId : "다른 호환 노드 자동 배치"}
            {o.trigger && <span className="block muted">{({ MANUAL: "사용자 요청", CPU: "CPU 사용률 기준 충족", MEMORY: "메모리 사용률 기준 충족", LATENCY: "서비스 지연 기준 충족" })[o.trigger]}</span>}
            {o.decision && <details><summary>자동 판단 근거 · 측정 {o.decision.samples.length}개</summary><OffloadPolicySummary value={o.decision.policy} /><p className="hint">판단 시각: {new Date(o.decision.evaluatedAt).toLocaleString()}</p><p className="digest">제외 노드: {o.excludedNodeNames.join(", ")}</p><pre aria-label="자동 전환 판단 근거">{JSON.stringify(o.decision, null, 2)}</pre></details>}
            {o.failureReason && <span className="block muted">실패 코드: {o.failureReason}</span>}
            {!!o.members?.length && <details><summary>함께 전환하는 스트리밍 작업 {o.members.length}개</summary>
              <p className="hint">선택한 작업은 새 노드로 이동하고, 다른 작업은 기존 배치 정책을 유지합니다. 모든 작업이 새 실행을 시작해야 전환 성공입니다.</p>
              <ul>{o.members.map(m => <li key={m.taskId}><strong>{run.tasks.find(t => t.id === m.taskId)?.key || m.taskId}</strong> · {m.targetNodeId ? nodes.find(n => n.id === m.targetNodeId)?.name || m.targetNodeId : "자동 배치"}
                <p className="digest mono">체크포인트 {m.checkpointId}</p><p className="digest mono">새 Attempt {m.targetAttemptId || "이전 실행 종료 대기"}</p>
              </li>)}</ul>
            </details>}
            <details><summary>전환 이력 ID</summary><p className="digest mono">Operation {o.id}</p><p className="digest mono">이전 Attempt {o.sourceAttemptId}</p><p className="digest mono">새 Attempt {o.targetAttemptId || "아직 생성되지 않음"}</p></details>
          </li>)}</ul> : <p className="muted">실행 위치 전환 이력이 없습니다.</p>}
        </div>}
        {task && results && <div role="region" aria-label={`검증된 결과 · ${task.task.key}`}>
          <div className="toolbar"><h3>검증된 결과 · {task.task.key}</h3><button disabled={busy} onClick={() => void action(() => showTask(task.task.id))}>결과 새로고침</button></div>
          {results.items.length === 0 ? <p className="muted">아직 확정된 결과가 없습니다.</p> : results.items.map(result => <div key={result.id}>
            <p className="hint">파일 검증 완료 · {new Date(result.createdAt).toLocaleString()}</p>
            {result.remoteAllocationId && <p className="hint">{result.remoteSourceMode === "SYNTHETIC" ? "Remote 참조 계산 · 실장비 검증 아님" : result.remoteSourceMode === "EXTERNAL" ? "외부 Remote 실행" : "Remote 실행 · 출처 확인 필요"}</p>}
            {result.vdRuntimeId && <p className="hint digest">가상 장치 실행 결과 · VD Runtime {result.vdRuntimeId} · Pod {result.producerPodUid}</p>}
            <div className="table-scroll"><table><caption className="sr-only">검증된 출력 파일</caption><thead><tr><th>출력 포트</th><th>크기</th><th>형식</th></tr></thead><tbody>{result.artifacts.map(artifact => <tr key={artifact.port}><td>{artifact.port}</td><td>{artifact.bytes.toLocaleString()} bytes</td><td>{artifact.mediaType}</td></tr>)}</tbody></table></div>
            <details><summary>결과 ID·체크섬·파일 버전</summary><p className="digest mono">Result {result.id}</p><p className="digest mono">Attempt {result.attemptId}</p>{result.remoteAllocationId && <p className="digest mono">RemoteAllocation {result.remoteAllocationId}</p>}{result.artifacts.map(artifact => <div key={artifact.port}><h4>{artifact.port}</h4><p className="digest mono">SHA-256 {artifact.sha256}</p><p className="digest mono">버전 {artifact.objectVersion}</p><p className="digest mono">{artifact.bucket}/{artifact.objectKey}</p></div>)}</details>
          </div>)}
        </div>}
        <details><summary>실행 매개변수·작업 상세</summary><pre aria-label="실행 상세 JSON">{runJson}</pre></details>
        {!terminal.has(run.run.state) && <div className="release-controls"><button disabled={busy} onClick={() => setCancel({ kind: "run", id: run.run.id })}>실행 취소</button></div>}
        {cancel && <div className="notice"><p>{cancel.kind === "run" ? "이 실행에 속한 모든 작업을 취소합니다." : "선택한 작업, 같은 스트림 그룹의 작업, 후속 의존 작업을 취소합니다. 독립된 분기는 계속 실행합니다."}</p><div className="toolbar"><button disabled={busy} onClick={() => void action(confirmCancellation)}>취소 확정</button><button disabled={busy} onClick={() => setCancel(null)}>계속 진행</button></div></div>}
      </section>}
    </>}
  </>;
}
