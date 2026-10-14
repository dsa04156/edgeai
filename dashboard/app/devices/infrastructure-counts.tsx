type Count = { key: string; label: string; count: number | null; pending: boolean };

export function InfrastructureCounts({ items, selected, select }: { items: Count[]; selected: string; select: (key: string) => void }) {
  return <div className="infrastructure-counts" role="group" aria-label="인프라 개수">
    {items.map(item => <button key={item.key} type="button" className="infrastructure-count" aria-label={item.label} aria-pressed={selected === item.key} aria-describedby={`infrastructure-count-${item.key}`} aria-busy={item.pending} onClick={() => select(item.key)}>
      <span>{item.label}</span><strong id={`infrastructure-count-${item.key}`}>{item.count == null ? "—" : `${item.count}개`}</strong>
      <small>{item.pending ? "조회 중" : item.count == null ? "확인 필요" : item.key === "virtual" ? "해제 이력 제외" : "전체 등록"}</small>
    </button>)}
  </div>;
}
