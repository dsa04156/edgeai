import Link from "next/link";

type Tone = "good" | "bad" | "warning" | "running" | "neutral" | "unknown";
type Group = { label: string; states: readonly string[]; tone: Tone };
const colors: Record<Tone, string> = { good: "var(--signal)", bad: "var(--error)", warning: "var(--warning)", running: "var(--running)", neutral: "var(--muted)", unknown: "var(--control-border)" };
export const nodeStatusGroups: readonly Group[] = [
  { label: "준비됨", states: ["READY"], tone: "good" }, { label: "준비 안 됨", states: ["NOT_READY"], tone: "bad" }, { label: "미확인", states: [], tone: "unknown" },
];
export const sensorStatusGroups: readonly Group[] = [
  { label: "온라인", states: ["ONLINE"], tone: "good" }, { label: "오프라인", states: ["OFFLINE"], tone: "bad" }, { label: "관측 만료", states: ["STALE"], tone: "warning" }, { label: "미확인", states: [], tone: "unknown" },
];
export const virtualStatusGroups: readonly Group[] = [
  { label: "계산 중", states: ["RUNNING"], tone: "good" }, { label: "입력 대기", states: ["WAITING_INPUT"], tone: "warning" }, { label: "전환 중", states: ["STARTING", "STOPPING"], tone: "running" },
  { label: "중지·비활성", states: ["STOPPED", "DISABLED"], tone: "neutral" }, { label: "오류", states: ["ERROR"], tone: "bad" }, { label: "미확인", states: [], tone: "unknown" },
];

export function ResourceStatusCard({ title, href, states, groups, pending, note }: { title: string; href: string; states: readonly string[] | null; groups: readonly Group[]; pending: boolean; note: string }) {
  const counts = groups.map(() => 0);
  for (const state of states ?? []) { const index = groups.findIndex(group => group.states.includes(state)); counts[index < 0 ? groups.length - 1 : index]++; }
  const total = states?.length;
  let edge = 0;
  const stops = groups.flatMap((group, index) => {
    if (!counts[index] || !total) return [];
    const start = edge; edge += counts[index] / total * 100;
    return [`${colors[group.tone]} ${start}% ${edge}%`];
  });
  const description = total == null ? `${title}: ${pending ? "불러오는 중" : "상태를 확인할 수 없습니다"}` : `${title}: 전체 ${total}개, ${groups.map((group, index) => `${group.label} ${counts[index]}개`).join(", ")}`;
  return <article className="resource-status-card" aria-label={`${title} 상태`} aria-busy={pending}>
    <h2><Link href={href} aria-label={`${title} 상태 상세`}><span>{title}</span><span aria-hidden="true">↗</span></Link></h2>
    <div className="resource-status-body"><div className="resource-status-donut" role="img" aria-label={description} style={{ background: stops.length ? `conic-gradient(${stops.join(", ")})` : "var(--line)" }}>
      <div className="resource-status-total" aria-hidden="true"><span>전체</span><strong className={total != null && total >= 10000 ? "compact-count" : undefined}>{total == null ? "—" : total.toLocaleString()}</strong><span>{total == null ? pending ? "확인 중" : "미확인" : "개"}</span></div>
    </div>
    <dl className="resource-status-legend">{groups.map((group, index) => <div key={group.label}><dt><i aria-hidden="true" style={{ background: colors[group.tone] }} />{group.label}</dt><dd>{total == null ? "—" : counts[index].toLocaleString()}</dd></div>)}</dl></div>
    <p className="resource-status-note">{total == null ? pending ? "상태를 불러오는 중…" : "상태를 확인할 수 없습니다" : total === 0 ? "등록된 항목이 없습니다" : note}</p>
  </article>;
}
