import Link from "next/link";

type Group = { label: string; states: readonly string[] };
export const nodeStatusGroups: readonly Group[] = [
  { label: "준비됨", states: ["READY"] }, { label: "준비 안 됨", states: ["NOT_READY"] }, { label: "미확인", states: [] },
];
export const sensorStatusGroups: readonly Group[] = [
  { label: "온라인", states: ["ONLINE"] }, { label: "오프라인", states: ["OFFLINE"] }, { label: "관측 만료", states: ["STALE"] }, { label: "미확인", states: [] },
];
export const virtualStatusGroups: readonly Group[] = [
  { label: "계산 중", states: ["RUNNING"] }, { label: "입력 대기", states: ["WAITING_INPUT"] }, { label: "전환 중", states: ["STARTING", "STOPPING"] },
  { label: "중지·비활성", states: ["STOPPED", "DISABLED"] }, { label: "오류", states: ["ERROR"] }, { label: "미확인", states: [] },
];

export function ResourceStatusCard({ title, href, states, groups, pending, note }: { title: string; href: string; states: readonly string[] | null; groups: readonly Group[]; pending: boolean; note: string }) {
  const counts = groups.map(() => 0);
  for (const state of states ?? []) { const index = groups.findIndex(group => group.states.includes(state)); counts[index < 0 ? groups.length - 1 : index]++; }
  const total = states?.length;
  const description = total == null ? `${title}: ${pending ? "불러오는 중" : "상태를 확인할 수 없습니다"}` : `${title}: 전체 ${total}개, ${groups.map((group, index) => `${group.label} ${counts[index]}개`).join(", ")}`;
  return <article className="resource-status-card" aria-label={`${title} 상태`} aria-busy={pending}>
    <h2><Link href={href} aria-label={`${title} 상태 상세`}><span>{title}</span><span aria-hidden="true">↗</span></Link></h2>
    <div className="resource-status-body"><div className="resource-status-number" role="group" aria-label={description}>
      <strong aria-hidden="true">{total == null ? "—" : total.toLocaleString()}</strong><span aria-hidden="true">{total == null ? pending ? "확인 중" : "미확인" : "전체"}</span>
    </div>
    <dl className="resource-status-legend">{groups.map((group, index) => (total == null ? index !== groups.length - 1 : !counts[index]) ? null : <div key={group.label}><dt>{total == null ? pending ? "조회 중" : "확인 필요" : group.label}</dt><dd>{total == null ? "—" : counts[index].toLocaleString()}</dd></div>)}</dl></div>
    <p className="resource-status-note" hidden={total != null && total > 0 && !counts[groups.length - 1]}>{total == null ? pending ? "상태를 불러오는 중…" : "상태를 확인할 수 없습니다" : total === 0 ? "등록된 항목이 없습니다" : note}</p>
  </article>;
}
