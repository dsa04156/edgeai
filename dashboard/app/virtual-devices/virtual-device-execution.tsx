"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import type { components } from "../../lib/api-schema";

type Schema = components["schemas"];
type Action = "provision" | "replace" | "drain";
const actions = { provision: "실행 시작", replace: "실행 교체", drain: "실행 종료" };
const kinds = { VD_PROVISION: "시작", VD_REPLACE: "교체", VD_DRAIN: "종료" };
const states = { RUNNING: "진행 중", SUCCEEDED: "완료", FAILED: "실패", SUPERSEDED: "새 요청으로 대체됨" };
const observations = { PENDING: "생성 대기", SUBMITTED: "Pod 생성됨", READY: "준비 관측됨", UNREADY: "준비되지 않음", TERMINATED: "종료 확인됨" };

function requestKey() {
  // getRandomValues also works on the existing private HTTP development ingress.
  const b = crypto.getRandomValues(new Uint8Array(16)); b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
  const h = Array.from(b, v => v.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export function VirtualDeviceExecution({ vd, auth, csrf, disabled }: { vd: Schema["VirtualDevice"]; auth: string; csrf: string; disabled: boolean }) {
  const [snapshot, setSnapshot] = useState<{ value: Schema["VDExecution"]; requestedAt: number } | null>(null);
  const [error, setError] = useState(""); const [commandError, setCommandError] = useState("");
  const [notice, setNotice] = useState(""); const [busy, setBusy] = useState(false); const commandBusy = useRef(false);
  const [draft, setDraft] = useState<{ action: Action; key: string; revision: number } | null>(null);
  const [now, setNow] = useState(0); const sequence = useRef({ value: 0 });
  const refresh = useCallback(async () => {
    const request = ++sequence.current.value; const requestedAt = performance.now();
    try {
      const response = await fetch(`/api/control-plane/virtual-devices/${vd.id}/execution`, { cache: "no-store", headers: { Authorization: auth }, signal: AbortSignal.timeout(8000) });
      const value = await response.json();
      if (!response.ok) throw new Error(value.message || "실행 상태를 조회하지 못했습니다.");
      if (request === sequence.current.value) { setSnapshot({ value, requestedAt }); setNow(performance.now()); setError(""); }
    } catch (e) { if (request === sequence.current.value) setError(e instanceof Error ? e.message : "실행 상태를 조회하지 못했습니다."); }
  }, [vd.id, auth]);
  useEffect(() => {
    const requests = sequence.current;
    let stopped = false; let timer: ReturnType<typeof setTimeout>;
    const poll = async () => { await refresh(); if (!stopped) timer = setTimeout(poll, 3000); };
    void poll(); const clock = setInterval(() => setNow(performance.now()), 1000);
    return () => { stopped = true; ++requests.value; clearTimeout(timer); clearInterval(clock); };
  }, [refresh, vd.revision]);
  const data = snapshot?.value; const current = data?.current;
  const leaseRemaining = current?.leaseUntil && snapshot ? Date.parse(current.leaseUntil) - Date.parse(data!.asOf) - (now - snapshot.requestedAt) : 0;
  const ready = !error && current?.ready && Number(leaseRemaining) > 0;
  async function submit() {
    if (!draft || commandBusy.current) return;
    commandBusy.current = true; setBusy(true); setCommandError(""); setNotice("");
    try {
      const response = await fetch(`/api/control-plane/virtual-devices/${vd.id}/${draft.action}`, { method: "POST", cache: "no-store", signal: AbortSignal.timeout(8000),
        headers: { Authorization: auth, "X-CSRF-TOKEN": csrf, "Idempotency-Key": draft.key, "Content-Type": "application/json" }, body: JSON.stringify({ revision: draft.revision }) });
      const value = await response.json();
      if (!response.ok) throw new Error(value.message || "실행 요청을 처리하지 못했습니다.");
      const operation = value as Schema["VDOperation"];
      setNotice(`${actions[draft.action]} 요청을 저장했습니다. Operation ${operation.id}`); setDraft(null); await refresh();
    } catch (e) { setCommandError(e instanceof Error ? e.message : "실행 요청을 처리하지 못했습니다."); }
    finally { commandBusy.current = false; setBusy(false); }
  }
  function prepare(action: Action) { setDraft({ action, key: requestKey(), revision: vd.revision }); setCommandError(""); setNotice(""); }
  return <section aria-labelledby="vd-execution-title">
    <div className="toolbar"><h3 id="vd-execution-title">실행 연결 · Runtime Binding</h3><button disabled={busy || disabled} onClick={() => void refresh()}>실행 상태 새로고침</button></div>
    <p className="hint">등록과 실행 준비는 별개입니다. 상태는 3초마다 갱신됩니다. 준비된 VD를 Workflow 작업의 실행 위치로 선택하면 같은 SERVICE 버전의 작업을 배정할 수 있습니다.</p>
    {error && <p role="alert" className="error">{error} 현재 준비 상태를 확인할 수 없습니다.</p>}
    {!data && !error && <p role="status">실행 상태 조회 중…</p>}
    {data && <>
      {!data.enabled && <p className="notice">VD 실행 기능이 비활성화되어 있습니다. 저장된 이력은 조회할 수 있습니다.</p>}
      <p className="stage">{error ? "상태 확인 필요" : !current ? "활성 실행 없음" : ready ? "실행 준비됨" : current.desiredState !== "RUNNING" ? "실행 종료 중" : current.observedState === "READY" ? "준비 권한 만료 · 확인 대기" : observations[current.observedState]}</p>
      {current && <dl><div><dt>실행 세대</dt><dd>{current.generation}세대 · 설정 revision {current.requestedRevision}</dd></div>
        <div><dt>실제 실행 노드</dt><dd className="digest">{current.nodeName ?? "배치 확인 대기"}</dd></div>
        <div><dt>Runtime ID</dt><dd className="mono digest">{current.id}</dd></div>
        {current.failureReason && <div><dt>종료 원인</dt><dd>{current.failureReason}</dd></div>}</dl>}
      {data.pendingOperation && <p role="status">{kinds[data.pendingOperation.kind]} 진행 중 · 실제 준비 또는 종료 확인을 기다립니다.</p>}
      <p className="hint">조회 시각 {data.asOf}</p>
      {!draft ? <div className="toolbar">
        <button disabled={busy || disabled || !!error || !data.enabled || vd.state !== "REGISTERED" || !!current || !!data.pendingOperation} onClick={() => prepare("provision")}>실행 시작</button>
        <button disabled={busy || disabled || !!error || !data.enabled || vd.state !== "REGISTERED" || !current || !!data.pendingOperation} onClick={() => prepare("replace")}>실행 교체</button>
        <button disabled={busy || disabled || !!error || !data.enabled || !current || data.pendingOperation?.kind === "VD_DRAIN"} onClick={() => prepare("drain")}>실행 종료</button>
      </div> : <div className="release-controls">
        <p>{draft.action === "replace" ? "이전 실행 종료 후 새 세대를 시작합니다. VD ID와 이력은 유지됩니다." : draft.action === "drain" ? "실행을 종료합니다. VD 등록과 원본 연결은 유지됩니다." : "현재 VD 설정으로 실행을 시작합니다. 준비가 확인되면 상태가 갱신됩니다."}</p>
        <p className="mono digest">요청 키 {draft.key} · revision {draft.revision}</p>
        <div className="toolbar"><button disabled={busy || disabled} onClick={() => void submit()}>{busy ? "요청 중…" : commandError ? "동일 요청 재전송" : `${actions[draft.action]} 요청`}</button>
          <button disabled={busy || disabled} onClick={() => { setDraft(null); setCommandError(""); }}>요청 닫기</button></div>
        {commandError && <p className="hint">응답이 불확실하면 같은 요청을 재전송하세요. revision 충돌이면 VD 새로고침 후 요청을 닫고 다시 작성하세요.</p>}
      </div>}
      {commandError && <p role="alert" className="error">{commandError}</p>}
      {notice && <p role="status" className="notice digest">{notice}</p>}
      <h4>실행 작업 이력</h4>
      <p className="hint">시작·교체 완료는 실행 준비 확인, 종료 완료는 실제 실행 제거 확인입니다. 작업 결과의 성공을 뜻하지 않습니다.</p>
      {data.operationsTruncated && <p className="hint">최근 작업 100개를 표시합니다.</p>}
      {data.operations.length ? <ul className="history-list">{data.operations.map(operation => <li key={operation.id}>
        <strong>{kinds[operation.kind]} · {states[operation.state]}</strong><span className="block mono digest">Operation {operation.id}</span>
        {operation.reason && <span className="block">{operation.reason}</span>}
      </li>)}</ul> : <p className="muted">아직 실행 작업이 없습니다.</p>}
      <details><summary>실행 연결·세대 이력</summary>
        {data.runtimeHistoryTruncated && <p className="hint">최근 실행 100개를 표시합니다.</p>}
        {data.runtimeHistory.length ? <ul className="history-list">{data.runtimeHistory.map(runtime => <li key={runtime.id}>
          <strong>{runtime.generation}세대 · {observations[runtime.observedState]}</strong><span className="block mono digest">{runtime.id}</span><span>{runtime.nodeName ?? "실행 노드 미확정"}</span>
        </li>)}</ul> : <p className="muted">실행 이력이 없습니다.</p>}
        {data.bindingsTruncated && <p className="hint">최근 실행 연결 100개를 표시합니다.</p>}
        {data.bindings.map(binding => <p key={binding.id} className="digest">{binding.closedAt ? "종료된 연결" : "현재 연결"} · revision {binding.openedRevision} → {binding.closedRevision ?? "현재"}<span className="block mono">{data.runtimeHistory.find(runtime => runtime.id === binding.runtimeId)?.nodeName || "실행 노드 미확정"} · {data.runtimeHistory.find(runtime => runtime.id === binding.runtimeId)?.generation ?? "—"}세대</span><span className="block">{new Date(binding.openedAt).toLocaleString()} → {binding.closedAt ? new Date(binding.closedAt).toLocaleString() : "현재"}</span></p>)}
      </details>
    </>}
  </section>;
}
