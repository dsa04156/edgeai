"use client";

// Adapted from Platform-Service/flow_project's ReactFlow canvas and K8sNode.
// Edges become EdgeAI DAG dependencies; execution uses the existing Spring API.
import { useState, useCallback, memo } from "react";
import { parse, stringify } from "lossless-json";
import { ReactFlow, Background, Controls, Handle, Position, addEdge, useNodesState, useEdgesState,
  type Node, type NodeProps, type Edge, type Connection } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import type { components } from "../../lib/api-schema";

type Profile = components["schemas"]["ProfileVersion"];
type Data = { label: string; key: string; profileId: string; inputs: string[]; outputs: string[]; parameters: Record<string, unknown> };
type ServiceNode = Node<Data, "service">;
type Link = Edge<{ mode: "BATCH" | "STREAM" }>;
function ports(profile: Profile, direction: "inputs" | "outputs") {
  const spec = profile.spec as Record<string, unknown>;
  const stream = spec.stream as Record<string, unknown> | undefined;
  return [...new Set([...Object.keys(spec[direction] as object || {}), ...Object.keys(stream?.[direction] as object || {})])];
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
  const [nodes, setNodes, onNodesChange] = useNodesState<ServiceNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Link>([]);
  const [profileId, setProfileId] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const current = nodes.find(n => n.id === selected);
  const connect = useCallback((connection: Connection) => {
    if (connection.source === connection.target || !connection.sourceHandle || !connection.targetHandle) {
      setError("서로 다른 작업의 출력 포트와 입력 포트를 연결하세요."); return;
    }
    const source = nodes.find(n => n.id === connection.source);
    const profile = profiles.find(p => p.id === source?.data.profileId);
    const streamOutputs = (profile?.spec as { stream?: { outputs?: object } } | undefined)?.stream?.outputs;
    const mode = streamOutputs && connection.sourceHandle in streamOutputs ? "STREAM" : "BATCH";
    setEdges(previous => addEdge({ ...connection, data: { mode }, label: mode }, previous)); setError(""); setNotice("");
  }, [nodes, profiles, setEdges]);
  function addService() {
    const profile = profiles.find(p => p.id === profileId); if (!profile) return;
    let index = nodes.length + 1;
    while (nodes.some(n => n.id === `task-${index}` || n.data.key === `task-${index}`)) index++;
    const key = `task-${index}`;
    setNodes(previous => [...previous, { id: key, type: "service", position: { x: (previous.length % 3) * 260 + 30, y: Math.floor(previous.length / 3) * 160 + 30 },
      data: { key, profileId, label: `${profile.key} · ${profile.version}`, inputs: ports(profile, "inputs"), outputs: ports(profile, "outputs"), parameters: {} } }]);
    setSelected(key); setNotice("");
  }
  function loadJson() {
    try {
      const dag = parse(value) as components["schemas"]["WorkflowDag"];
      if (!Array.isArray(dag.tasks) || !Array.isArray(dag.dependencies)) throw new Error("tasks와 dependencies가 있는 DAG JSON을 입력하세요.");
      const next: ServiceNode[] = dag.tasks.map((task, index) => {
        const profile = profiles.find(p => p.id === task.serviceProfileVersionId);
        if (!profile) throw new Error(`서비스 버전을 먼저 조회하세요: ${task.serviceProfileVersionId}`);
        return { id: task.key, type: "service", position: { x: (index % 3) * 260 + 30, y: Math.floor(index / 3) * 160 + 30 },
          data: { key: task.key, profileId: profile.id, label: `${profile.key} · ${profile.version}`, inputs: ports(profile, "inputs"), outputs: ports(profile, "outputs"), parameters: task.parameters } };
      });
      setNodes(next); setEdges(dag.dependencies.map((edge, i) => ({ id: `edge-${i}`, source: edge.fromTask, target: edge.toTask,
        sourceHandle: edge.fromPort, targetHandle: edge.toPort, data: { mode: edge.mode }, label: edge.mode })));
      setSelected(null); setError(""); setNotice("JSON을 편집 화면으로 불러왔습니다.");
    } catch (e) { setError(e instanceof Error ? e.message : "DAG JSON을 확인하세요."); }
  }
  function apply() {
    const keys = nodes.map(n => n.data.key);
    if (!nodes.length || new Set(keys).size !== keys.length || keys.some(key => !/^[a-z][a-z0-9]*([._-][a-z0-9]+)*$/.test(key))) {
      setError("서비스를 추가하고 작업 키를 중복 없이 지정하세요."); return;
    }
    const keyById = new Map(nodes.map(n => [n.id, n.data.key]));
    const dag = { tasks: nodes.map(n => ({ key: n.data.key, serviceProfileVersionId: n.data.profileId, parameters: n.data.parameters })),
      dependencies: edges.map(e => ({ fromTask: keyById.get(e.source), toTask: keyById.get(e.target), fromPort: e.sourceHandle,
        toPort: e.targetHandle, mode: e.data?.mode || "BATCH" })) };
    onApply(stringify(dag, null, 2) || ""); setError(""); setNotice("편집 내용을 반영했습니다. 아래에서 버전을 발행한 뒤 실행 노드를 선택하세요.");
  }
  return <div className="workflow-editor" aria-label="워크플로 시각 편집기">
    <p className="hint">서비스를 배치하고 출력·입력 포트를 연결하세요. 연결선이 실제 DAG 의존 관계로 저장됩니다.</p>
    <div className="toolbar filter-form"><label>추가할 SERVICE<select value={profileId} onChange={e => setProfileId(e.target.value)} disabled={disabled}>
      <option value="">서비스 선택</option>{profiles.map(p => <option key={p.id} value={p.id}>{p.key} · {p.version}</option>)}
    </select></label><button type="button" disabled={disabled || !profileId} onClick={addService}>작업 추가</button>
      <button type="button" disabled={disabled} onClick={loadJson}>JSON 불러오기</button></div>
    <div className="workflow-canvas"><ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes}
      onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onConnect={connect}
      onNodeClick={(_, node) => setSelected(node.id)} nodesDraggable={!disabled} nodesConnectable={!disabled}
      deleteKeyCode={disabled ? null : ["Backspace", "Delete"]} fitView minZoom={0.3} maxZoom={1.5}>
      <Background /><Controls showInteractive={false} />
    </ReactFlow></div>
    {current && <div className="toolbar filter-form"><label>선택한 작업 키<input value={current.data.key} maxLength={100} disabled={disabled}
      onChange={e => { const key = e.target.value; setNodes(previous => previous.map(n => n.id === current.id ? { ...n, data: { ...n.data, key } } : n)); setNotice(""); }} /></label>
      <button type="button" disabled={disabled} onClick={() => { setNodes(previous => previous.filter(n => n.id !== current.id)); setEdges(previous => previous.filter(e => e.source !== current.id && e.target !== current.id)); setSelected(null); }}>선택 작업 삭제</button></div>}
    {!!edges.length && <ul className="history-list">{edges.map(edge => <li key={edge.id}>
      {nodes.find(n => n.id === edge.source)?.data.key}.{edge.sourceHandle} → {nodes.find(n => n.id === edge.target)?.data.key}.{edge.targetHandle}
      <select aria-label={`${edge.source}에서 ${edge.target} 연결 방식`} disabled={disabled} value={edge.data?.mode || "BATCH"} onChange={e => {
        const mode = e.target.value as "BATCH" | "STREAM"; setEdges(previous => previous.map(item => item.id === edge.id ? { ...item, label: mode, data: { mode } } : item));
      }}><option value="BATCH">BATCH · 완료 후 전달</option><option value="STREAM">STREAM · 실시간 전달</option></select>
      <button type="button" disabled={disabled} onClick={() => setEdges(previous => previous.filter(item => item.id !== edge.id))}>연결 삭제</button>
    </li>)}</ul>}
    {error && <p role="alert" className="error">{error}</p>}{notice && <p role="status" className="notice">{notice}</p>}
    <button type="button" className="primary" disabled={disabled || !nodes.length} onClick={apply}>편집 내용을 DAG에 반영</button>
  </div>;
}
