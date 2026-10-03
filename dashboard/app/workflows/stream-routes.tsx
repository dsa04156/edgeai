"use client";
import { useState } from "react";
import type { components } from "../../lib/api-schema";

type Schema = components["schemas"];
const states: Record<string, string> = { PREPARING: "연결 준비", ACTIVE: "연결 활성", FENCED: "전송 차단", CLOSED: "연결 종료" };
const sources: Record<string, string> = { SYNTHETIC: "합성 데이터", REPLAY: "기록 재생", LIVE: "실제 장치" };

export function StreamRoutes({ tasks, fetchPage, disabled }: {
  tasks: Schema["Task"][]; fetchPage: (offset: number) => Promise<Schema["StreamRoutePage"]>; disabled: boolean;
}) {
  const [page, setPage] = useState<Schema["StreamRoutePage"] | null>(null);
  const [offset, setOffset] = useState(0); const [busy, setBusy] = useState(false); const [error, setError] = useState("");
  const name = (id: string) => tasks.find(t => t.id === id)?.key || id;
  async function load(next: number) {
    setBusy(true); setError("");
    try { setPage(await fetchPage(next)); setOffset(next); }
    catch (e) { setError(e instanceof Error ? e.message : "스트림 경로를 조회하지 못했습니다."); }
    finally { setBusy(false); }
  }
  return <section aria-label="스트림 경로" aria-busy={busy}>
    <div className="toolbar"><h3>스트림 경로</h3><button disabled={disabled || busy} onClick={() => void load(offset)}>{page ? "스트림 경로 새로고침" : "스트림 경로 조회"}</button></div>
    <p className="hint">같은 그룹의 작업은 함께 시작합니다. 연결 상태와 메시지 한도를 확인하고, 원본 세션·실행 시도는 경로 상세에서 확인하세요.</p>
    {busy && <p role="status">스트림 경로를 조회하고 있습니다.</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {!page && !busy && !error && <p className="muted">조회하면 이 실행에 고정된 데이터 경로를 표시합니다.</p>}
    {page && (page.items.length ? <ul className="history-list" aria-label="실행의 스트림 경로와 세대 상태">
      {page.items.map(route => <li key={route.id}>
        <strong className="digest">{route.sourceTaskId ? name(route.sourceTaskId) : `장치 ${route.sourceDeviceId?.slice(0, 8)}`} · {route.sourcePort} → {name(route.consumerTaskId)} · {route.consumerPort}</strong>
          {route.sourceMode && <span className="block muted">{sources[route.sourceMode] || route.sourceMode}</span>}
          <p>{route.generation ? `${states[route.generation.state] || route.generation.state} · 세대 ${route.generation.number}` : "작업 배정 대기"}<span className="block muted">메시지 한도 {route.maxPayloadBytes.toLocaleString()} B · {route.mediaType}</span></p>
          <details><summary>경로 상세</summary><dl className="digest">
            <div><dt>경로 ID</dt><dd className="mono">{route.id}</dd></div><div><dt>그룹 ID</dt><dd className="mono">{route.componentId || "미지정"}</dd></div>
            {route.sourceDeviceId && <><div><dt>원본 장치 ID</dt><dd className="mono">{route.sourceDeviceId}</dd></div><div><dt>고정 세션 / epoch</dt><dd className="mono">{route.sourceSessionId || "미지정"} / {route.sourceEpoch ?? "—"}</dd></div></>}
            <div><dt>원본 Profile 버전</dt><dd className="mono">{route.sourceProfileVersionId}</dd></div>
            {route.generation && <><div><dt>소비 실행 시도 / epoch</dt><dd className="mono">{route.generation.consumerAttemptId} / {route.generation.consumerEpoch}</dd></div>
              <div><dt>연결 기한</dt><dd>{new Date(route.generation.leaseUntil).toLocaleString()}</dd></div>
              {route.generation.fenceReason && <div><dt>차단 사유</dt><dd>{route.generation.fenceReason}</dd></div>}</>}
          </dl></details>
      </li>)}
    </ul> : <p className="empty">이 실행에 등록된 스트림 경로가 없습니다.</p>)}
    {page && <div className="pagination"><button disabled={disabled || busy || offset === 0} onClick={() => void load(Math.max(0, offset - 20))}>이전 경로</button><span>{offset / 20 + 1} 페이지</span><button disabled={disabled || busy || page.nextOffset == null} onClick={() => void load(page.nextOffset!)}>다음 경로</button></div>}
  </section>;
}
