import type { components } from "../../lib/api-schema";
import { hardwareLabel } from "../../lib/hardware";
type Schema = components["schemas"];
export function PlacementTable({ placements, nodes, onSelect }: { placements: Schema["Placement"][]; nodes: Schema["ExecutionNode"][]; onSelect: (taskId: string) => void }) {
  return <section aria-label="작업별 배치 현황"><h3>Placement · 실제 실행 위치</h3>
    <div className="table-scroll"><table><caption className="sr-only">작업별 요청 정책과 실제 배치</caption><thead><tr><th>ServiceTask</th><th>요청 정책</th><th>실제 노드 · 자원</th><th>Runtime 상태 · 원인</th></tr></thead>
      <tbody>{placements.map(p => {
        const node = nodes.find(n => n.name === p.runtime?.nodeName);
        return <tr key={p.taskId}><td><button className="version-link" onClick={() => onSelect(p.taskId)}>{p.taskKey}</button><span className="block muted">{p.taskState}</span></td>
          <td>{p.attempt ? `${p.attempt.mode} · 시도 ${p.attempt.number}` : "선행 작업 대기"}</td>
          <td>{p.runtime?.nodeName || (p.attempt?.mode === "REMOTE" ? "원격 제공자" : "배치 관측 대기")}{node && <span className="block muted">{node.architecture} · {hardwareLabel(node)}</span>}</td>
          <td>{p.runtime?.observedState || "Runtime 생성 전"}{p.runtime?.failureReason && <span className="block error">{p.runtime.failureReason}</span>}
            {p.runtime && <details><summary>Pod · Job 정보</summary><p className="digest">{p.runtime.namespace} / {p.runtime.jobName || "공유 실행"}</p><p className="mono digest">Pod {p.runtime.podUid || "관측 전"}</p><p className="hint">{new Date(p.runtime.updatedAt).toLocaleString()}</p></details>}</td></tr>;
      })}</tbody></table></div>
  </section>;
}
