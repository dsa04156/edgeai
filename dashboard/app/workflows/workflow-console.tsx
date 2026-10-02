"use client";
import { useState, type FormEvent } from "react";
import { parse, stringify } from "lossless-json";
import type { components, operations } from "../../lib/api-schema";
import { ConnectionPanel } from "../components/connection-panel";

type Schema = components["schemas"];
const stateNames: Record<string, string> = { PENDING: "실행 대기", WAITING: "입력 대기", READY: "실행 준비", QUEUED: "접수됨", DISPATCHING: "배치 중", RUNNING: "실행 중", SUCCEEDED: "성공", FAILED: "실패", CANCELLING: "종료 확인 중", CANCELLED: "취소됨", SKIPPED: "건너뜀", OFFLOADED: "전환됨" };
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
  const [mode, setMode] = useState("AUTO"); const [parameters, setParameters] = useState("{}");
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
    if (workflow?.workflow.id !== id) { setVersion(null); setVersionJson(""); }
    setWorkflow(value); setVersionOffset(offset);
  }
  async function showVersion(workflowId: string, number: string) {
    const text = await (await api(`workflows/${workflowId}?version=${encodeURIComponent(number)}`)).text();
    const value = JSON.parse(text).versions[0]; setVersion(value);
    setVersionJson(stringify((parse(text) as { versions: { dag: unknown }[] }).versions[0].dag, null, 2) || "");
    setKey(requestKey()); setCancel(null);
  }
  async function showRun(id: string) {
    const text = await (await api(`workflow-runs/${id}`)).text(); setRun(JSON.parse(text)); setRunJson(stringify(parse(text), null, 2) || ""); setTask(null); setCancel(null);
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
    setAuth(""); setCsrf(""); setWorkflows(null); setWorkflow(null); setVersion(null); setVersionJson(""); setProfiles([]); setNodes([]);
    setRuns(null); setRun(null); setRunJson(""); setTask(null); setCancel(null); setKey(""); setError(""); setNotice(""); setDag('{"tasks": [], "dependencies": []}'); setParameters("{}");
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
          const response = await post("workflow-runs", { workflowVersionId: version.id, execution: mode === "AUTO" ? { mode } : { mode, nodeId }, parameters: objectJson(parameters) }, key);
          const value = await response.json(); await showRun(value.id); await loadRuns(0); setNotice(response.status === 201 ? "실행 요청을 저장했습니다. 작업은 실행 대기 상태입니다." : "동일한 실행 요청을 조회했습니다. 새 실행은 만들지 않았습니다.");
        }); }}><fieldset disabled={busy} className="publish-fields">
          <label>실행 위치 정책<select value={mode} onChange={e => setMode(e.target.value)}><option value="AUTO">자동 선택 (AUTO)</option><option value="NODE">노드 지정 (NODE)</option></select></label>
          {mode === "NODE" && <label>실행 노드 ID<input name="nodeId" list="workflow-execution-nodes" required maxLength={36} placeholder="관측된 Node UUID" /></label>}
          <datalist id="workflow-execution-nodes">{nodes.map(n => <option key={n.id} value={n.id}>{n.name} · {n.architecture}</option>)}</datalist>
          <label>실행 매개변수 JSON<textarea rows={4} value={parameters} onChange={e => setParameters(e.target.value)} spellCheck={false} /></label>
          <label>실행 요청 키<input value={key} readOnly className="mono" /></label><p className="hint">같은 키·내용을 다시 보내면 기존 실행을 반환합니다. 다른 실행을 만들 때 새 키를 발급하세요.</p>
          <div className="toolbar"><button type="button" onClick={() => setKey(requestKey())}>새 실행 키 만들기</button><button className="primary">실행 요청 저장</button></div>
        </fieldset></form>
      </section>}
      <section aria-labelledby="runs-title"><div className="toolbar"><h2 id="runs-title">실행 이력</h2><button disabled={busy} onClick={() => void action(async () => { await loadRuns(); if (run) await showRun(run.run.id); })}>실행 새로고침</button></div>
        {runs?.items.length ? <div className="table-scroll"><table><caption className="sr-only">실행 요청 목록</caption><thead><tr><th>Run ID</th><th>상태 / 정책</th><th>생성 시각</th></tr></thead><tbody>{runs.items.map(r => <tr key={r.id}><td><button className="version-link mono" disabled={busy} onClick={() => void action(() => showRun(r.id))}>{r.id}</button></td><td>{stateNames[r.state]}<span className="block muted">{r.mode}</span></td><td>{new Date(r.createdAt).toLocaleString()}</td></tr>)}</tbody></table></div> : <p className="empty">아직 실행 요청이 없습니다. 발행한 DAG 버전을 선택해 요청을 만드세요.</p>}
        <div className="pagination"><button disabled={busy || runOffset === 0} onClick={() => void action(() => loadRuns(runOffset - 20))}>이전 실행</button><span>{runOffset / 20 + 1} 페이지</span><button disabled={busy || runs?.nextOffset == null} onClick={() => void action(() => loadRuns(runs!.nextOffset!))}>다음 실행</button></div>
      </section>
      {run && <section className="panel" aria-labelledby="run-detail-title"><div className="toolbar"><h2 id="run-detail-title">선택한 실행</h2><span className="stage">{stateNames[run.run.state]}</span></div><p className="digest mono">Run ID {run.run.id}</p>
        <div className="table-scroll"><table><caption className="sr-only">실행에 속한 작업</caption><thead><tr><th>작업</th><th>상태</th><th>작업 제어</th></tr></thead><tbody>{run.tasks.map(t => <tr key={t.id}><td><button className="version-link" disabled={busy} onClick={() => void action(async () => setTask(await (await api(`tasks/${t.id}`)).json()))}>{t.key}</button></td><td>{stateNames[t.state]}{t.cancellationReason === "UPSTREAM_CANCELLED" && <span className="block muted">선행 작업 취소</span>}</td><td>{!terminal.has(t.state) && <button disabled={busy} onClick={() => setCancel({ kind: "task", id: t.id })} aria-label={`${t.key} 작업 취소`}>작업 취소</button>}</td></tr>)}</tbody></table></div>
        {task && <div><h3>실행 시도 · {task.task.key}</h3>{task.attempts.length ? <ul className="history-list">{task.attempts.map(a => <li key={a.id}>Attempt #{a.number} · epoch {a.epoch} · {stateNames[a.state]}<span className="block mono digest">{a.id}</span></li>)}</ul> : <p className="muted">{task.task.state === "WAITING" ? "선행 작업을 기다리는 중이며 아직 실행 시도가 없습니다." : "생성된 실행 시도가 없습니다."}</p>}</div>}
        <details><summary>실행 매개변수·작업 상세</summary><pre aria-label="실행 상세 JSON">{runJson}</pre></details>
        {!terminal.has(run.run.state) && <div className="release-controls"><button disabled={busy} onClick={() => setCancel({ kind: "run", id: run.run.id })}>실행 취소</button></div>}
        {cancel && <div className="notice"><p>{cancel.kind === "run" ? "이 실행에 속한 모든 작업을 취소합니다." : "선택한 작업과 아직 실행하지 않은 하위 의존 작업을 취소합니다."}</p><div className="toolbar"><button disabled={busy} onClick={() => void action(confirmCancellation)}>취소 확정</button><button disabled={busy} onClick={() => setCancel(null)}>계속 진행</button></div></div>}
      </section>}
    </>}
  </>;
}
