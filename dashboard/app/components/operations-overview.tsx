"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import type { components } from "../../lib/api-schema";
import { registryItems } from "../../lib/registry";
import { PageHeading } from "./page-heading";
import { StatusBadge } from "./status-badge";
import { useNodeMetrics } from "../../lib/node-metrics";
import { NodeMetricsNotice } from "./node-usage";
import { monitoredNodes } from "../../lib/monitored-nodes";
import { MonitoredNodes } from "./monitored-nodes";
import { infrastructureLabels, useInfrastructure, type InfrastructureKind } from "../../lib/infrastructure";
import { ResourceStatusCard, nodeStatusGroups, sensorStatusGroups, virtualStatusGroups } from "./resource-status-card";
import { virtualDeviceStatuses, type VirtualDeviceStatuses } from "../../lib/virtual-device-status";

type Schema = components["schemas"];
type Snapshot = { nodes: Schema["ExecutionNode"][] | null; devices: Schema["ManagedDevice"][] | null; profiles: Schema["ProfileVersion"][] | null; runs: Schema["RunPage"] | null; errors: string[]; at: Date };
const overviewRequest = (signal: AbortSignal) => async (path: string) => {
  const response = await fetch(`/api/control-plane/${path}`, { cache: "no-store", signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]) });
  if (!response.ok) throw new Error("조회 실패");
  return response;
};
const time = (value: string) => new Date(value).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });

export function OperationsOverview({ workflows }: { workflows: boolean }) {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [refresh, setRefresh] = useState(0);
  const metrics = useNodeMetrics(true, refresh);
  const [busy, setBusy] = useState(true);
  const [virtual, setVirtual] = useState<{ value: VirtualDeviceStatuses | null; error: string | null; pending: boolean }>({ value: null, error: null, pending: true });
  const infrastructure = useInfrastructure(refresh);
  useEffect(() => {
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout>;
    const request = overviewRequest(controller.signal);
    const profilesRequest = registryItems<Schema["ProfileVersion"]>("profiles/SERVICE", request);
    async function load() {
      const values = await Promise.allSettled([
        registryItems<Schema["ExecutionNode"]>("nodes", request),
        request("managed-devices").then(r => r.json()).then((page: Schema["ManagedDevicePage"]) => page.items),
        workflows ? request("workflow-runs?limit=20&offset=0").then(r => r.json() as Promise<Schema["RunPage"]>) : Promise.resolve(null),
        profilesRequest,
      ]);
      if (controller.signal.aborted) return;
      const [nodes, devices, runs, profiles] = values;
      setSnapshot({ nodes: nodes.status === "fulfilled" ? nodes.value : null, devices: devices.status === "fulfilled" ? devices.value : null, runs: runs.status === "fulfilled" ? runs.value : null,
        profiles: profiles.status === "fulfilled" ? profiles.value : null,
        errors: values.flatMap((result, index) => result.status === "rejected" ? [["실행 노드", "센서 상태", "실행 이력", "서비스 프로필"][index]] : []), at: new Date() });
      setBusy(false); timer = setTimeout(load, 10000);
    }
    void load(); return () => { controller.abort(); clearTimeout(timer); };
  }, [workflows, refresh]);
  useEffect(() => {
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout>;
    const request = overviewRequest(controller.signal);
    async function load() {
      try {
        const value = await virtualDeviceStatuses(request);
        if (!controller.signal.aborted) setVirtual({ value, error: value.failures ? `가상디바이스 실행 상태 ${value.failures}개` : null, pending: false });
      } catch {
        if (!controller.signal.aborted) setVirtual({ value: null, error: "가상디바이스 목록", pending: false });
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(load, 10000);
      }
    }
    void load(); return () => { controller.abort(); clearTimeout(timer); };
  }, [refresh]);
  const nodes = snapshot?.nodes;
  const devices = snapshot?.devices;
  const runs = snapshot?.runs?.items;
  const monitored = monitoredNodes(metrics, nodes || [], infrastructure.snapshot);
  const classified = infrastructure.snapshot?.nodesStatus === "AVAILABLE" ? infrastructure.snapshot.nodes : null;
  const sensors = infrastructure.snapshot?.sensorsStatus === "AVAILABLE" ? infrastructure.snapshot.sensors : null;
  const sensorPending = (!infrastructure.snapshot && !infrastructure.failed) || !snapshot;
  const sensorStates = sensorPending ? null : sensors?.map(sensor => devices?.find(device => device.id === sensor.name)?.state ?? "UNKNOWN") ?? null;
  const errors = [...(snapshot?.errors ?? []), ...(virtual.error ? [virtual.error] : [])];
  return <>
    <PageHeading eyebrow="WORKSPACE / OVERVIEW" title="운영 현황" description="AI 서비스와 현장 장비의 상태를 한눈에 확인하세요."
      action={<button disabled={busy} onClick={() => { setBusy(true); setVirtual(previous => ({ ...previous, pending: true })); setRefresh(value => value + 1); }}>{busy ? "불러오는 중…" : "새로고침"}</button>} />
    <div className="overview-meta"><span className="small-label">자원 현황</span><span>{snapshot ? `${snapshot.at.toLocaleTimeString("ko-KR")} 조회 · 10초마다 갱신` : "서버에서 상태를 확인하고 있습니다"}</span></div>
    {!!errors.length && <p className="error" role="alert">{errors.join(" · ")} 조회에 실패했습니다. 해당 항목은 확인할 수 없음으로 표시합니다.</p>}
    <div className="resource-status-strip" aria-label="자원 현황" aria-busy={busy || virtual.pending}>
      <ResourceStatusCard title="엣지 AI 서버" href="/devices?view=servers" states={classified?.filter(node => node.kind === "EDGE_AI_SERVER").map(node => node.status) ?? null} groups={nodeStatusGroups} pending={!infrastructure.snapshot && !infrastructure.failed} note="현재 노드 준비 상태" />
      <ResourceStatusCard title="엣지 디바이스" href="/devices?view=edge" states={classified?.filter(node => node.kind === "EDGE_DEVICE").map(node => node.status) ?? null} groups={nodeStatusGroups} pending={!infrastructure.snapshot && !infrastructure.failed} note="현재 노드 준비 상태" />
      <ResourceStatusCard title="센서" href="/devices?view=sensors" states={sensorStates} groups={sensorStatusGroups} pending={sensorPending} note={devices ? "측정 항목별 연결 상태" : "연결 상태 확인 필요"} />
      <ResourceStatusCard title="가상디바이스" href="/devices?view=virtual" states={virtual.value?.states ?? null} groups={virtualStatusGroups} pending={virtual.pending} note={virtual.value?.failures ? "일부 실행 상태 확인 필요" : "실제 계산 실행 상태"} />
    </div>
    <div className="overview-grid">
      <section className="console-panel node-panel" aria-labelledby="cluster-title">
        <div className="panel-heading"><div><h2 id="cluster-title">노드별 자원 현황 {!!monitored.length && <span className="count-badge">{monitored.length}개 노드</span>}</h2></div><Link href="/devices?view=nodes">자원 상세 →</Link></div>
        <NodeMetricsNotice state={metrics} />
        {infrastructure.failed && <p className="error" role="status">장비 분류와 센서 목록을 불러오지 못했습니다.</p>}
        {(["EDGE_AI_SERVER", "EDGE_DEVICE", ...(monitored.some(node => node.kind === "UNKNOWN") ? ["UNKNOWN"] : [])] as InfrastructureKind[]).map(kind => <section key={kind} aria-label={infrastructureLabels[kind]} className="infrastructure-group">
          <h3>{infrastructureLabels[kind]} <span className="count-badge">{classified ? monitored.filter(node => node.kind === kind).length : "—"}</span></h3>
          <MonitoredNodes state={metrics} inventory={nodes || []} infrastructure={infrastructure.snapshot} kind={kind} />
        </section>)}
      </section>
      <section className="console-panel operation-guide" aria-labelledby="pipeline-title"><div className="panel-heading"><div><p className="small-label">OPERATE YOUR PIPELINE</p><h2 id="pipeline-title">실행 준비</h2></div><span className="mono muted">01 — {workflows ? "03" : "02"}</span></div>
        <ol className="pipeline-steps">{[
          ["/profiles", "Profile 등록", "장치 · 서비스 규격과 버전 정의"], ["/devices", "장치 연결", "실장치와 실행 노드 확인"], ...(workflows ? [["/workflows", "워크플로 실행", "서비스 연결 → 발행 → 실행 요청"]] : []),
        ].map(([href, title, description], index) => <li key={href}><Link href={href}><span className="step-number mono">0{index + 1}</span><div><strong>{title}</strong><small>{description}</small></div><span aria-hidden="true">↗</span></Link></li>)}</ol>
      </section>
    </div>
    {workflows && <section className="console-panel recent-runs" aria-labelledby="recent-runs-title"><div className="panel-heading"><div><p className="small-label">RECENT EXECUTIONS</p><h2 id="recent-runs-title">최근 실행</h2></div><Link href="/runs">모든 실행 보기 ↗</Link></div>
      {!snapshot ? <p className="empty">실행 이력을 불러오는 중…</p> : !runs ? <p className="empty">실행 이력을 확인할 수 없습니다.</p> : !runs.length ? <div className="empty"><strong>아직 실행 요청이 없습니다.</strong><p>서비스를 연결하고 첫 워크플로를 실행하세요.</p><Link href="/workflows" className="button-link primary">Workflow Builder 열기</Link></div> : <div className="table-scroll"><table><caption className="sr-only">최근 실행 요청</caption><thead><tr><th>실행</th><th>상태</th><th>배치 정책</th><th>요청 시각</th><th><span className="sr-only">상세</span></th></tr></thead><tbody>{runs.slice(0, 6).map(run => <tr key={run.id}><td><Link className="run-id mono" href={`/runs?run=${run.id}`} title={run.id}>{run.id.slice(0, 8)}<span className="muted">…{run.id.slice(-4)}</span></Link></td><td><StatusBadge state={run.state} /></td><td>{run.mode === "NODE" ? nodes?.find(n => n.id === run.nodeId)?.name || "노드 지정" : { AUTO: "자동 배치", REMOTE: "원격 실행", VD: "과거 가상디바이스 실행" }[run.mode]}</td><td className="muted">{time(run.createdAt)}</td><td><Link href={`/runs?run=${run.id}`} aria-label={`${run.id} 실행 상세`}>상세 ↗</Link></td></tr>)}</tbody></table></div>}
    </section>}
  </>;
}
