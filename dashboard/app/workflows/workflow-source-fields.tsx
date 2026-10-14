"use client";

import { useEffect, useState } from "react";
import type { components } from "../../lib/api-schema";

type Catalog = components["schemas"]["WorkflowDataSources"];
type Selection = components["schemas"]["WorkflowSourceSelection"];
function object(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
function selectionKey(value: unknown) {
  const source = object(value);
  if (source.kind === "SENSOR" && typeof source.deviceId === "string" && typeof source.property === "string") return `SENSOR:${source.deviceId}:${source.property}`;
  if (source.kind === "CONTINUOUS_VD" && typeof source.vdId === "string" && typeof source.outputId === "string") return `CONTINUOUS_VD:${source.vdId}:${source.outputId}`;
  return "";
}

export function WorkflowSourceFields({ value, onChange }: { value: unknown; onChange: (value: Selection | { kind: string }) => void }) {
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [loading, setLoading] = useState(true), [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      try {
        const response = await fetch("/api/control-plane/workflow-data-sources", { cache: "no-store", signal: controller.signal });
        const body = await response.json();
        if (!response.ok) throw new Error(body.message || "입력 목록을 불러오지 못했습니다.");
        if (!Array.isArray(body.items)) throw new Error("입력 목록 형식을 확인하세요.");
        if (!controller.signal.aborted) { setCatalog(body); setError(""); }
      } catch (failure) {
        if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : "입력 목록을 불러오지 못했습니다.");
      } finally { if (!controller.signal.aborted) setLoading(false); }
    }
    void load();
    return () => controller.abort();
  }, [refresh]);
  const kind = object(value).kind === "CONTINUOUS_VD" ? "CONTINUOUS_VD" : "SENSOR";
  const options = catalog?.items.filter(item => item.selection.kind === kind) || [];
  const selected = selectionKey(value);
  const current = options.find(item => selectionKey(item.selection) === selected);
  return <div className="publish-fields workflow-source-fields">
    <label>입력 종류<select aria-label="입력 종류" value={kind} onChange={event => onChange({ kind: event.target.value })}>
      <option value="SENSOR">센서 값</option><option value="CONTINUOUS_VD">가상디바이스 계산 결과</option>
    </select></label>
    <label>입력 데이터<select aria-label="입력 데이터" value={selected} disabled={loading} onChange={event => {
      const item = options.find(item => selectionKey(item.selection) === event.target.value);
      onChange(item ? item.selection : { kind });
    }}><option value="">{loading ? "입력 목록을 불러오는 중…" : "사용할 데이터 선택"}</option>
      {selected && !current && <option value={selected}>선택한 원본 · 목록 확인 필요</option>}
      {options.map(item => <option key={selectionKey(item.selection)} value={selectionKey(item.selection)}>
        {item.label}{item.unit ? ` · ${item.unit}` : ""}{item.available ? "" : " · 계산 결과 대기"}
      </option>)}
    </select></label>
    <p className="hint">1초마다 최신 유효값 처리 · 입력 변경은 새 버전·실행에 적용</p>
    {current && !current.available && <p className="hint">이 가상디바이스를 시작하고 계산 결과가 들어온 뒤 실행하세요.</p>}
    {!loading && catalog && !options.length && <p className="empty">선택할 {kind === "SENSOR" ? "검증된 센서 원본" : "가상디바이스 계산 출력"}이 없습니다.</p>}
    {catalog?.truncated && <p className="hint">입력 목록이 일부만 표시됩니다.</p>}
    {error && <p className="error" role="alert">{error}</p>}
    <button type="button" disabled={loading} onClick={() => { setLoading(true); setError(""); setRefresh(previous => previous + 1); }}>입력 목록 새로고침</button>
  </div>;
}
