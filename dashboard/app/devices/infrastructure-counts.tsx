import { ConsoleIcon } from "../components/console-shell";

type Count = { key: string; label: string; count: number | null; pending: boolean };

export function InfrastructureCounts({ items, selected, select }: { items: Count[]; selected: string; select: (key: string) => void }) {
  return <div className="infrastructure-counts" role="group" aria-label="인프라 개수">
    {items.map(item => <button key={item.key} type="button" className="infrastructure-count" aria-label={item.label} aria-pressed={selected === item.key} aria-describedby={`infrastructure-count-${item.key}`} aria-busy={item.pending} onClick={() => select(item.key)}>
      <ConsoleIcon name={item.key === "servers" ? "nodes" : item.key === "virtual" ? "runs" : "sensor"} /><span>{item.label}</span><strong id={`infrastructure-count-${item.key}`}>{item.count == null ? "—" : `${item.count}개`}</strong>
      <small hidden={!item.pending && item.count != null}>{item.pending ? "조회 중" : "확인 필요"}</small>
    </button>)}
  </div>;
}
