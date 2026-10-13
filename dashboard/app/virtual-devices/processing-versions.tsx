"use client";
import { useRef, useState } from "react";
import type { components } from "../../lib/api-schema";

type Page = components["schemas"]["ProcessingVersionPage"];
export function ProcessingVersions({ vdId, auth }: { vdId: string; auth: string }) {
  const [page, setPage] = useState<Page | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const requestSequence = useRef(0);
  async function load(offset = 0) {
    if (busy) return; setBusy(true); const sequence = ++requestSequence.current;
    try {
      const response = await fetch(`/api/control-plane/virtual-devices/${vdId}/processing-versions?limit=20&offset=${offset}`, { cache: "no-store", headers: { Authorization: auth }, signal: AbortSignal.timeout(8000) });
      const value = await response.json(); if (!response.ok) throw new Error(value.message || "처리 버전을 조회하지 못했습니다.");
      if (sequence === requestSequence.current) { setPage(previous => offset && previous ? { ...value, items: [...previous.items, ...value.items] } : value); setError(""); }
    } catch (error) { if (sequence === requestSequence.current) setError(error instanceof Error ? error.message : "처리 버전을 확인하세요."); }
    finally { if (sequence === requestSequence.current) setBusy(false); }
  }
  return <section aria-label="처리 정의 버전"><div className="toolbar"><h3>처리 정의 · 버전</h3><button disabled={busy} onClick={() => void load()}>처리 정의·버전 조회</button></div>
    <p className="hint">입력 연결·계산식·저장 정책을 변경할 때 이전 버전을 남깁니다. 저장된 정의로 규칙을 다시 만들 수 있으며, 계산 중이던 창의 복구는 별도 기능입니다.</p>
    {busy && <p role="status">처리 버전 조회 중…</p>}{error && <p role="alert" className="error">{error}</p>}
    {page && !page.items.length && <p className="hint">새 버전 이력 기능을 적용한 이후 저장한 처리 정의가 없습니다. 현재 정의는 등록된 프로필에서 유지됩니다.</p>}
    {page?.items.map(version => <details key={version.revision} className="publish-fields"><summary><strong>revision {version.revision}</strong> · {new Date(version.recordedAt).toLocaleString()}</summary><p className="hint">프로필 버전 {version.profileVersionId}</p><h4>처리 설정 · 저장 정책</h4><pre>{JSON.stringify(version.configuration, null, 2)}</pre><h4>입력 연결</h4><pre>{JSON.stringify(version.sources, null, 2)}</pre><h4>생성 SQL 템플릿</h4><p className="hint">실행 시 새 generation을 부여합니다. 이전 설정을 덮어쓰지 않습니다.</p>{version.rules.map(rule => <div key={rule.id}><strong>{rule.outputId}</strong><pre>{rule.sql}</pre></div>)}</details>)}
    {page?.nextOffset != null && <button disabled={busy || page.items.length >= 200} onClick={() => void load(page.nextOffset!)}>이전 처리 버전</button>}
  </section>;
}
