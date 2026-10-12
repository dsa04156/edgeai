"use client";

import { useEffect, useRef, useState } from "react";
import { isLosslessNumber, parse, stringify } from "lossless-json";
import { record } from "../../lib/vd-profile";

export function ResultContent({ taskId, port, supported }: { taskId: string; port: string; supported: boolean }) {
  const [value, setValue] = useState<unknown>();
  const [loaded, setLoaded] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);
  async function load() {
    const controller = new AbortController(); request.current = controller;
    setBusy(true); setError("");
    try {
      const response = await fetch(`/api/control-plane/tasks/${taskId}/results/${encodeURIComponent(port)}/content`, {
        cache: "no-store", signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.message || "결과 내용을 불러오지 못했습니다.");
      }
      const document: unknown = parse(await response.text());
      if (!controller.signal.aborted) { setValue(document); setLoaded(true); }
    } catch (cause) {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "결과 조회 실패");
    } finally { if (!controller.signal.aborted) setBusy(false); }
  }
  if (!supported) return <p className="hint">{port} · 내용 조회는 1 MiB 이하 JSON 파일을 지원합니다.</p>;
  const document = record(value), outputs = record(document.outputs);
  const display = (sample: unknown) => typeof sample === "boolean" ? sample ? "참" : "거짓" : isLosslessNumber(sample) ? sample.value : String(sample ?? "—");
  return <div>
    <button type="button" disabled={busy} onClick={() => void load()}>{port} · 결과 내용 조회</button>
    {busy && <p role="status">결과 파일을 검증하고 있습니다…</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {loaded && <>
      {document.apiVersion === "edgeai.processing-output/v1" && Object.entries(outputs).map(([name, raw]) => {
        const output = record(raw), values = Array.isArray(output.values) ? output.values : [];
        const times = Array.isArray(output.observedAt) ? output.observedAt : [];
        const frequencies = Array.isArray(output.frequenciesHz) ? output.frequenciesHz : [];
        return <div key={name}><div className="table-scroll"><table>
          <caption>{name} · 처리 결과</caption>
          <thead><tr><th>표본</th><th>값</th>{times.length > 0 && <th>관측 시각</th>}{frequencies.length > 0 && <th>주파수(Hz)</th>}</tr></thead>
          <tbody>{values.slice(0, 100).map((sample, index) => <tr key={index}><td>{index + 1}</td><td>{display(sample)}</td>{times.length > 0 && <td>{display(times[index])}</td>}{frequencies.length > 0 && <td>{display(frequencies[index])}</td>}</tr>)}</tbody>
        </table></div>{values.length > 100 && <p className="hint">처음 100개 표본을 표시합니다. 전체 값은 결과 JSON에서 확인하세요.</p>}</div>;
      })}
      <details><summary>{port} · 결과 JSON</summary><pre>{stringify(value, null, 2)}</pre></details>
    </>}
  </div>;
}
