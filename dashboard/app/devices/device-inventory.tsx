"use client";
import { useCallback, useState } from "react";
import type { components } from "../../lib/api-schema";
import { StatusBadge } from "../components/status-badge";
import { ConnectionPanel } from "../components/connection-panel";
import { hardwareLabel } from "../../lib/hardware";
import { useNodeMetrics } from "../../lib/node-metrics";
import { NodeMetricsNotice, NodeUsage } from "../components/node-usage";
import { MonitoredNodes } from "../components/monitored-nodes";
import { infrastructureLabels, useInfrastructure, type InfrastructureKind } from "../../lib/infrastructure";
import { ManagedDevices } from "../components/managed-devices";
import { VirtualDeviceRegistry } from "../virtual-devices/virtual-device-registry";
import { InfrastructureCounts } from "./infrastructure-counts";
import { useVirtualDeviceCount } from "../../lib/virtual-device-count";
import { ConsoleIcon } from "../components/console-shell";

type Node = components["schemas"]["ExecutionNode"];
export function DeviceInventory({ initialView = "servers", initialCreateOpen = false }: { initialView?: string; initialCreateOpen?: boolean }) {
  const [view,setView]=useState(initialView), [search,setSearch]=useState(""), [selectedNode,setSelectedNode]=useState("");
  const [createVirtual, setCreateVirtual] = useState(initialCreateOpen);
  const [auth,setAuth]=useState(false), [busy,setBusy]=useState(false), [error,setError]=useState("");
  const [metricsRefresh,setMetricsRefresh]=useState(0);
  const metrics=useNodeMetrics(view!=="sensors" && view!=="virtual",metricsRefresh), infrastructure=useInfrastructure(metricsRefresh);
  const virtual = useVirtualDeviceCount(metricsRefresh);
  const refreshCounts = useCallback(() => setMetricsRefresh(value => value + 1), []);
  const classified = infrastructure.snapshot?.nodesStatus === "AVAILABLE" ? infrastructure.snapshot.nodes : null;
  const sensors = infrastructure.snapshot?.sensorsStatus === "AVAILABLE" ? infrastructure.snapshot.sensors : null;
  const pending = !infrastructure.snapshot && !infrastructure.failed;
  const group: InfrastructureKind=view==="servers"?"EDGE_AI_SERVER":view==="edge"?"EDGE_DEVICE":"UNKNOWN";
  const [nodes,setNodes]=useState<Node[]>([]), [nodeOffset,setNodeOffset]=useState(0), [nextNodeOffset,setNextNodeOffset]=useState<number|null>(null);
  async function loadNodes(nextOffset=nodeOffset) {
    const response=await fetch(`/api/control-plane/nodes?limit=20&offset=${nextOffset}`,{cache:"no-store"});
    if(!response.ok)throw new Error("실행 노드 조회에 실패했습니다.");
    const value: components["schemas"]["NodePage"]=await response.json();setNodes(value.items);setNodeOffset(nextOffset);setNextNodeOffset(value.nextOffset);
  }
  async function run(action:()=>Promise<void>) {setBusy(true);setError("");try{await action();}catch(e){setError(e instanceof Error?e.message:"조회하지 못했습니다.");}finally{setBusy(false);}}
  function connect() {void run(async()=>{
    const query=new URLSearchParams(window.location.search), requested=query.get("view");
    const nextView=requested==="devices"||query.has("device")||query.has("profile")?"sensors":["servers","edge","sensors","virtual","unknown"].includes(requested||"")?requested!:"servers";setView(nextView);
    setSelectedNode(query.get("node")||"");setSearch(query.get("search")||"");
    if(nextView!=="sensors" && nextView!=="virtual")await loadNodes(0);setAuth(true);
  });}
  function chooseView(value: string) {
    setView(value); setSearch(""); setCreateVirtual(false);
    const url = new URL(window.location.href); url.searchParams.set("view", value); url.searchParams.delete("create"); window.history.replaceState(window.history.state, "", url);
    if (value !== "sensors" && value !== "virtual") void run(() => loadNodes(0));
  }
  return <>
    <ConnectionPanel connected={auth} busy={busy} onConnect={connect}/>
    {error&&<p role="alert" className="error">{error}</p>}
    <div className="inventory-workspace"><aside className="inventory-sidebar" aria-label="리소스 보기"><h2>리소스</h2><p className="hint">전체 등록 기준</p><InfrastructureCounts selected={view} select={chooseView} items={[
      { key: "servers", label: "엣지 AI 서버", count: classified?.filter(node => node.kind === "EDGE_AI_SERVER").length ?? null, pending },
      { key: "edge", label: "엣지 디바이스", count: classified?.filter(node => node.kind === "EDGE_DEVICE").length ?? null, pending },
      { key: "sensors", label: "센서", count: sensors?.length ?? null, pending },
      { key: "virtual", label: "가상디바이스", count: virtual.count, pending: virtual.pending },
    ]} />
    {(infrastructure.snapshot?.nodesStatus!=="AVAILABLE"||infrastructure.snapshot.nodes.some(node=>node.kind==="UNKNOWN")) && <button className="unknown-resources" aria-pressed={view === "unknown"} onClick={() => chooseView("unknown")}>분류 미확인</button>}</aside><div className="inventory-content">
    <div className="inventory-controls"><label><span className="sr-only">현재 페이지에서 검색</span><ConsoleIcon name="search" /><input type="search" value={search} onChange={e=>setSearch(e.target.value)} placeholder="이름 · 장비 · 측정 항목 검색"/></label></div>
    {view==="sensors"&&<section className="console-panel"><ManagedDevices search={search}/></section>}
    {view === "virtual" && <VirtualDeviceRegistry embedded search={search} initiallyOpen={createVirtual} onInventoryChange={refreshCounts} />}
      {view !== "sensors" && view !== "virtual" && <section aria-labelledby="nodes-title"><div className="toolbar"><h2 id="nodes-title">{infrastructureLabels[group]}</h2><button disabled={busy} onClick={() => { setMetricsRefresh(value => value + 1); void run(() => loadNodes()); }}>새로고침</button></div>
        <NodeMetricsNotice state={metrics} />
        {infrastructure.snapshot?.nodesStatus !== "AVAILABLE" && <p role="status">장비 분류를 확인할 수 없습니다. 수집된 노드는 ‘분류 미확인’에서 볼 수 있습니다.</p>}
        <MonitoredNodes state={metrics} inventory={nodes} infrastructure={infrastructure.snapshot} kind={group} search={search} />
        <details className="create-panel"><summary>Kubernetes 실행 노드 기록</summary>
        <p className="hint">별도로 저장된 Kubernetes 관측 기록입니다. 60초 넘게 갱신되지 않으면 관측 만료로 표시합니다. 자원은 노드의 할당 가능량이며 현재 남은 용량과 다릅니다.</p>
        {nodes.length ? <div className="table-scroll"><table><caption className="sr-only">Kubernetes 실행 노드 목록</caption><thead><tr><th>노드 / 아키텍처</th><th>상태</th><th>할당 가능량</th><th>GPU · NPU 구성</th><th>CPU · 메모리 · 가속기 사용량</th><th>노드 관측 시각</th></tr></thead><tbody>{nodes.filter(n => `${n.name} ${n.architecture} ${hardwareLabel(n)}`.toLowerCase().includes(search.toLowerCase())).map(n => <tr key={n.id} data-selected={selectedNode === n.id}><td>{n.name}<span className="block muted mono">{n.architecture} · {n.operatingSystem}</span></td><td><StatusBadge state={n.status} /></td><td className="mono">CPU {n.cpu}<span className="block">메모리 {n.memory}</span></td><td>{hardwareLabel(n)}</td><td className="node-usage-cell"><NodeUsage node={n} state={metrics} detailed /></td><td>{new Date(n.observedAt).toLocaleString()}</td></tr>)}</tbody></table></div> : <p className="empty">관측된 노드가 없습니다. Kubernetes 연결 설정과 관측 상태를 확인하세요.</p>}
        {!!nodes.length && !nodes.some(n => `${n.name} ${n.architecture} ${hardwareLabel(n)}`.toLowerCase().includes(search.toLowerCase())) && <p className="empty">현재 페이지에서 일치하는 노드가 없습니다.</p>}
        <div className="pagination"><button disabled={busy || nodeOffset === 0} onClick={() => void run(() => loadNodes(Math.max(0, nodeOffset - 20)))}>이전 노드</button><span>{nodeOffset / 20 + 1} 페이지</span><button disabled={busy || nextNodeOffset === null} onClick={() => void run(() => loadNodes(nextNodeOffset!))}>다음 노드</button></div>
        </details>
      </section>}
    </div></div>
  </>;
}
