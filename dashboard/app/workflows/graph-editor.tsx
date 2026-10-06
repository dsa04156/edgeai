"use client";

// ReactFlow editing adapted from the partner tool; the DAG remains the single source of truth.
import { useState, useMemo, memo } from "react";
import { parse, stringify } from "lossless-json";
import { ReactFlow, Background, Controls, Handle, Position, type Node, type NodeProps, type Connection } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import type { components } from "../../lib/api-schema";

type Profile = components["schemas"]["ProfileVersion"];
type Dag = components["schemas"]["WorkflowDag"];
type Data = { label: string; key: string; inputs: string[]; outputs: string[] };
type ServiceNode = Node<Data, "service">;
function ports(profile: Profile | undefined, direction: "inputs" | "outputs") {
  const spec = profile?.spec as Record<string, unknown> | undefined;
  const stream = spec?.stream as Record<string, unknown> | undefined;
  return [...new Set([...Object.keys(spec?.[direction] as object || {}), ...Object.keys(stream?.[direction] as object || {})])];
}
const ServiceCard = memo(function ServiceCard({ data, selected }: NodeProps<ServiceNode>) {
  return <div className={`workflow-card${selected ? " selected" : ""}`}>
    <strong>{data.key}</strong><span className="block muted">{data.label}</span>
    <div className="workflow-ports"><div>{data.inputs.map(port => <div className="workflow-port" key={port}>
      <Handle type="target" position={Position.Left} id={port} /><span>{port}</span>
    </div>)}</div><div>{data.outputs.map(port => <div className="workflow-port" key={port}>
      <span>{port}</span><Handle type="source" position={Position.Right} id={port} />
    </div>)}</div></div>
  </div>;
});
const nodeTypes = { service: ServiceCard };

export function GraphEditor({ profiles, value, onApply, disabled }: {
  profiles: Profile[]; value: string; onApply: (value: string) => void; disabled: boolean;
}) {
  const dag = useMemo(() => {
    try { const parsed = parse(value) as Dag; return Array.isArray(parsed.tasks) && Array.isArray(parsed.dependencies) ? parsed : null; }
    catch { return null; }
  }, [value]);
  const [profileId, setProfileId] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const [positions, setPositions] = useState<Record<string, { x: number; y: number }>>({});
  const [error, setError] = useState("");
  const [source, setSource] = useState(""); const [target, setTarget] = useState("");
  const nodes: ServiceNode[] = (dag?.tasks || []).map((task, index) => {
    const profile = profiles.find(p => p.id === task.serviceProfileVersionId);
    return { id: task.key, type: "service", selected: selected === task.key,
      position: positions[task.key] || { x: (index % 3) * 270 + 25, y: Math.floor(index / 3) * 170 + 25 },
      data: { key: task.key, label: profile ? `${profile.key} · ${profile.version}` : task.serviceProfileVersionId,
        inputs: ports(profile, "inputs"), outputs: ports(profile, "outputs") } };
  });
  const current = dag?.tasks.find(task => task.key === selected);
  function commit(next: Dag) { onApply(stringify(next, null, 2) || ""); setError(""); }
  function connect(connection: Connection) {
    if (!dag) return;
    const { source: fromTask, target: toTask, sourceHandle: fromPort, targetHandle: toPort } = connection;
    if (!nodes.some(n => n.id === fromTask && n.data.outputs.includes(fromPort || "")) || !nodes.some(n => n.id === toTask && n.data.inputs.includes(toPort || ""))) { setError("현재 작업의 포트를 다시 선택하세요."); return; }
    if (fromTask === toTask || !fromPort || !toPort) { setError("서로 다른 작업의 출력과 입력을 연결하세요."); return; }
    if (dag.dependencies.some(edge => edge.toTask === toTask && edge.toPort === toPort)) { setError("이미 연결된 입력 포트입니다. 기존 연결을 먼저 삭제하세요."); return; }
    const reachable = new Set([toTask]);
    for (let index = 0; index < dag.tasks.length; index++)
      for (const edge of dag.dependencies) if (reachable.has(edge.fromTask)) reachable.add(edge.toTask);
    if (reachable.has(fromTask)) { setError("순환 연결은 만들 수 없습니다."); return; }
    const spec = (key: string) => profiles.find(p => p.id === dag.tasks.find(t => t.key === key)?.serviceProfileVersionId)?.spec as { stream?: { inputs?: object; outputs?: object } } | undefined;
    const fromStream = fromPort in (spec(fromTask)?.stream?.outputs || {});
    const toStream = toPort in (spec(toTask)?.stream?.inputs || {});
    if (fromStream !== toStream) { setError("BATCH와 STREAM 포트는 서로 연결할 수 없습니다."); return; }
    commit({ ...dag, dependencies: [...dag.dependencies, { fromTask, toTask, fromPort, toPort, mode: fromStream ? "STREAM" : "BATCH" }] });
  }
  function remove(key: string) {
    if (!dag) return;
    commit({ tasks: dag.tasks.filter(t => t.key !== key), dependencies: dag.dependencies.filter(e => e.fromTask !== key && e.toTask !== key) });
    setSelected(null);
  }
  return <div className="workflow-editor" aria-label="워크플로 시각 편집기">
    <p className="hint">SERVICE를 추가하고 포트를 연결하세요. 편집은 즉시 DAG에 반영됩니다. 버전을 발행하면 실행할 수 있습니다.</p>
    {!dag && <p className="error" role="alert">DAG JSON의 tasks와 dependencies를 확인하세요.</p>}
    <div className="toolbar filter-form"><label>추가할 SERVICE<select value={profileId} onChange={e => setProfileId(e.target.value)} disabled={disabled}>
      <option value="">서비스 선택</option>{profiles.map(p => <option key={p.id} value={p.id}>{p.key} · {p.version}</option>)}
    </select></label><button type="button" disabled={disabled || !profileId || !dag} onClick={() => {
      if (!dag) return;
      let index = dag.tasks.length + 1; while (dag.tasks.some(t => t.key === `task-${index}`)) index++;
      const key = `task-${index}`; commit({ ...dag, tasks: [...dag.tasks, { key, serviceProfileVersionId: profileId, parameters: {} }] }); setSelected(key);
    }}>작업 추가</button></div>
    {!profiles.length && <p><a href="/profiles?kind=SERVICE">SERVICE Profile 먼저 등록 →</a></p>}
    <div className="workflow-canvas"><ReactFlow nodes={nodes} nodeTypes={nodeTypes}
      edges={(dag?.dependencies || []).map((e, i) => ({ id: String(i), source: e.fromTask, target: e.toTask, sourceHandle: e.fromPort, targetHandle: e.toPort, label: e.mode }))}
      onNodesChange={changes => { if (!disabled) for (const change of changes) if (change.type === "position" && change.position) setPositions(previous => ({ ...previous, [change.id]: change.position! })); }}
      onConnect={connect} onNodeClick={(_, node) => setSelected(node.id)} nodesDraggable={!disabled} nodesConnectable={!disabled}
      deleteKeyCode={null} fitView minZoom={0.3} maxZoom={1.5}><Background /><Controls showInteractive={false} /></ReactFlow></div>
    <div className="toolbar filter-form"><label>편집할 작업<select value={selected || ""} onChange={e => setSelected(e.target.value)}><option value="">작업 선택</option>{nodes.map(n => <option key={n.id}>{n.id}</option>)}</select></label></div>
    {current && <fieldset className="publish-fields" disabled={disabled} key={current.key}><legend>{current.key} · ServiceTask</legend>
      <label>작업 키<input defaultValue={current.key} maxLength={100} onBlur={e => {
        const key = e.target.value.trim(); if (key === current.key || !dag) return;
        if (!/^[a-z][a-z0-9]*([._-][a-z0-9]+)*$/.test(key) || dag.tasks.some(t => t.key === key)) { setError("중복되지 않는 영문 소문자 작업 키를 입력하세요."); e.target.value = current.key; return; }
        commit({ tasks: dag.tasks.map(t => t.key === current.key ? { ...t, key } : t), dependencies: dag.dependencies.map(edge => ({ ...edge, fromTask: edge.fromTask === current.key ? key : edge.fromTask, toTask: edge.toTask === current.key ? key : edge.toTask })) }); setSelected(key);
      }} /></label>
      <label>작업 매개변수 JSON<textarea rows={3} defaultValue={stringify(current.parameters, null, 2) || "{}"} onBlur={e => {
        try { const parameters = parse(e.target.value); if (!parameters || typeof parameters !== "object" || Array.isArray(parameters)) throw new Error();
          if (dag) commit({ ...dag, tasks: dag.tasks.map(t => t.key === current.key ? { ...t, parameters: parameters as Record<string, unknown> } : t) });
        } catch { setError("작업 매개변수는 JSON 객체여야 합니다. 수정한 값이 반영되지 않았습니다."); }
      }} /></label>
      <button type="button" onClick={() => remove(current.key)}>선택 작업 삭제</button>
    </fieldset>}
    {!!nodes.length && <fieldset className="publish-fields" disabled={disabled}><legend>포트 연결</legend><div className="form-row">
      <label>출력 포트<select value={source} onChange={e => setSource(e.target.value)}><option value="">출력 선택</option>{nodes.flatMap(n => n.data.outputs.map(port => <option key={`${n.id}/${port}`} value={JSON.stringify([n.id, port])}>{n.id} · {port}</option>))}</select></label>
      <label>입력 포트<select value={target} onChange={e => setTarget(e.target.value)}><option value="">입력 선택</option>{nodes.flatMap(n => n.data.inputs.map(port => <option key={`${n.id}/${port}`} value={JSON.stringify([n.id, port])}>{n.id} · {port}</option>))}</select></label>
    </div><button type="button" disabled={!source || !target} onClick={() => { const [sourceId, sourceHandle] = JSON.parse(source); const [targetId, targetHandle] = JSON.parse(target); connect({ source: sourceId, sourceHandle, target: targetId, targetHandle }); }}>포트 연결 추가</button></fieldset>}
    {!!dag?.dependencies.length && <ul className="history-list">{dag.dependencies.map((edge, index) => <li key={index}>
      {edge.fromTask}.{edge.fromPort} → {edge.toTask}.{edge.toPort} · {edge.mode} <button type="button" disabled={disabled} aria-label={`${edge.fromTask} → ${edge.toTask} 연결 삭제`} onClick={() => commit({ ...dag, dependencies: dag.dependencies.filter((_, i) => i !== index) })}>연결 삭제</button>
    </li>)}</ul>}
    {error && <p role="alert" className="error">{error}</p>}
  </div>;
}
