"use client";
import { useState } from "react";
import type { components } from "../../lib/api-schema";
import { ConnectionPanel } from "../components/connection-panel";

type Audit = components["schemas"]["ManagementAudit"];
type AuditPage = components["schemas"]["ManagementAuditPage"];
const operations: Record<string, string> = {
  deleteProfileVersion: "프로필 버전 삭제", deleteDeviceRegistration: "장치 등록 삭제",
  deleteVirtualDeviceRegistration: "가상 장치 등록 영구 삭제", executeSensorCommand: "센서 명령 실행",
  publishProfile: "프로필 버전 발행", registerDevice: "장치 등록", updateDevice: "장치 수정", releaseDevice: "장치 해제",
  attachDevice: "장치·노드 연결", openDeviceSession: "장치 세션 시작", reportDeviceObservation: "장치 상태 보고",
  createWorkflow: "워크플로 등록", publishWorkflowVersion: "워크플로 버전 발행", createWorkflowRun: "워크플로 실행 요청",
  cancelWorkflowRun: "실행 취소 요청", cancelTask: "작업 취소 요청", offloadTask: "작업 실행 위치 전환",
  createVirtualDevice: "가상 장치 등록", updateVirtualDevice: "가상 장치 수정", releaseVirtualDevice: "가상 장치 해제",
  provisionVirtualDevice: "가상 장치 시작", replaceVirtualDeviceRuntime: "가상 장치 실행 교체", drainVirtualDeviceRuntime: "가상 장치 종료",
  issueDeviceStreamToken: "장치 스트림 토큰 발급", unmappedManagementRequest: "등록되지 않은 관리 요청",
};
function operation(value: Audit) { return operations[value.operation] || (["GET", "HEAD", "OPTIONS"].includes(value.method) ? "관리 정보 조회" : "관리 요청"); }
function actor(value: Audit) {
  const person = value.outcome?.actor;
  if (!person) return "확인되지 않음";
  if (person.type === "UNAUTHENTICATED") return "계정 없이 접수";
  return person.subjectFormat === "SHA256" ? "계정 식별 해시" : person.subject || "확인되지 않음";
}
function result(value: Audit) {
  if (!value.outcome) return "결과 미확인";
  const status = value.outcome.httpStatus;
  return `HTTP ${status} · ${status >= 500 ? "오류" : status >= 400 ? "거절" : status === 202 ? "접수" : "응답 기록됨"}`;
}

export function AuditHistory() {
  const [auth, setAuth] = useState(""); const [busy, setBusy] = useState(false); const [error, setError] = useState("");
  const [page, setPage] = useState<AuditPage | null>(null); const [offset, setOffset] = useState(0);
  const [detail, setDetail] = useState<Audit | null>(null);
  async function api(path: string, authorization = auth) {
    const response = await fetch(`/api/control-plane/${path}`, { cache: "no-store", headers: authorization.startsWith("Basic ") ? { Authorization: authorization } : {} });
    if (!response.ok) {
      const value = await response.json().catch(() => ({}));
      throw new Error(response.status === 401 ? "API 연결을 확인하세요." : response.status === 404 ? "해당 감사 기록을 찾을 수 없습니다." : value.message || "감사 기록을 불러오지 못했습니다.");
    }
    return response.json();
  }
  async function run(action: () => Promise<void>) {
    setBusy(true); setError("");
    try { await action(); } catch (failure) { setError(failure instanceof Error ? failure.message : "감사 기록을 불러오지 못했습니다."); }
    finally { setBusy(false); }
  }
  async function load(nextOffset = offset, authorization = auth) {
    setPage(null); setDetail(null);
    setPage(await api(`audit-requests?limit=20&offset=${nextOffset}`, authorization)); setOffset(nextOffset);
  }
  async function show(id: string) { setDetail(null); setDetail(await api(`audit-requests/${id}`)); }
  function connect() {
    void run(async () => {
      const authorization = "dashboard";
      await load(0, authorization); setAuth(authorization);
    });
  }

  return <>
    <ConnectionPanel connected={!!auth} busy={busy} onConnect={connect} />
    {error && <p role="alert" className="error">{error}</p>}
    {busy && <p role="status">감사 기록을 확인하는 중…</p>}
    {auth && <>
      <section aria-labelledby="audit-title" aria-busy={busy}>
        <div className="toolbar"><h2 id="audit-title">최근 관리 요청</h2><button disabled={busy} onClick={() => void run(() => load())}>감사 목록 새로고침</button></div>
        <p className="hint">변경 요청과 조회의 접근 거절을 접수 시각순으로 표시합니다. 정상 조회는 저장하지 않습니다.</p>
        {page?.items.length === 0 && <p className="empty">아직 저장된 감사 요청이 없습니다.</p>}
        {!!page?.items.length && <div className="table-scroll"><table className="audit-table"><caption className="sr-only">관리 요청 감사 목록</caption>
          <thead><tr><th>요청</th><th>계정</th><th>응답</th><th>접수 시각</th></tr></thead>
          <tbody>{page.items.map(value => <tr key={value.id}>
            <td><button disabled={busy} className="version-link" onClick={() => void run(() => show(value.id))}>{operation(value)}<span className="mono">{value.id.slice(0, 8)}</span></button></td>
            <td className="digest">{actor(value)}</td><td>{result(value)}</td><td>{new Date(value.startedAt).toLocaleString()}</td>
          </tr>)}</tbody></table></div>}
        <div className="pagination"><button disabled={busy || offset === 0} onClick={() => void run(() => load(Math.max(0, offset - 20)))}>이전 감사</button><span>{offset / 20 + 1} 페이지</span><button disabled={busy || page?.nextOffset == null} onClick={() => void run(() => load(page!.nextOffset!))}>다음 감사</button></div>
      </section>
      <section className="panel" aria-labelledby="audit-search-title"><h2 id="audit-search-title">감사 ID로 찾기</h2>
        <form className="toolbar filter-form" onSubmit={event => { event.preventDefault(); const id = String(new FormData(event.currentTarget).get("auditId")).trim(); void run(() => show(id)); }}>
          <label>감사 ID<input name="auditId" required minLength={36} maxLength={36} pattern="[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}" placeholder="응답에서 확인한 UUID" /></label>
          <button disabled={busy}>감사 조회</button>
        </form><p className="hint">API 응답의 X-EdgeAI-Audit-Id로 특정 요청을 확인할 수 있습니다.</p>
      </section>
      {detail && <section className="panel" aria-labelledby="audit-detail-title">
        <div className="toolbar"><h2 id="audit-detail-title">감사 요청 상세</h2><button disabled={busy} onClick={() => void run(() => show(detail.id))}>감사 상세 새로고침</button></div>
        <h3>{operation(detail)}</h3><p><strong>{result(detail)}</strong></p>
        {!detail.outcome ? <p className="empty">요청은 접수됐지만 결과 기록이 없습니다. 아직 처리 중이거나 기록이 중단됐을 수 있습니다. 원래 장치·실행 상태를 조회한 뒤 다음 조치를 결정하세요.</p>
          : <p className="hint">이 상태는 관리 API의 응답입니다. 실제 작업 완료 여부는 실행·작업 상세에서 확인하세요.</p>}
        <dl><div><dt>계정</dt><dd className="digest">{actor(detail)}</dd></div><div><dt>접수 시각</dt><dd>{new Date(detail.startedAt).toLocaleString()}</dd></div>
          <div><dt>응답 시각</dt><dd>{detail.outcome ? new Date(detail.outcome.completedAt).toLocaleString() : "기록 없음"}</dd></div>
          <div><dt>감사 ID</dt><dd className="mono digest">{detail.id}</dd></div>
          {detail.targetId && <div><dt>대상 ID</dt><dd className="mono digest">{detail.targetId}</dd></div>}
          {detail.relatedId && <div><dt>연관 대상 ID</dt><dd className="mono digest">{detail.relatedId}</dd></div>}
        </dl><details><summary>요청 식별 정보</summary><pre aria-label="감사 상세 JSON">{JSON.stringify(detail, null, 2)}</pre></details>
      </section>}
    </>}
  </>;
}
