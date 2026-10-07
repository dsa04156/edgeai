"use client";

// ReactFlow editing adapted from the partner tool; the DAG remains the single source of truth.
import { useState, useMemo, useRef, useEffect, memo } from "react";
import { parse, stringify } from "lossless-json";
import { ReactFlow, Background, Controls, Handle, Position, type Node, type NodeProps, MiniMap, MarkerType, type ReactFlowInstance, type Connection } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import type { components } from "../../lib/api-schema";
import { profilePorts as ports, connectTasks, readWorkflowDocument, arrangeWorkflow, type GraphPositions } from "../../lib/workflow-graph";

type Profile = components["schemas"]["ProfileVersion"];
type Dag = components["schemas"]["WorkflowDag"];
type Data = { label: string; key: string; inputs: string[]; outputs: string[]; category: string };
type ServiceNode = Node<Data, "service">;
const ServiceCard = memo(function ServiceCard({ data, selected }: NodeProps<ServiceNode>) {
  return <div className={`workflow-card${selected ? " selected" : ""}`}>
    <span className="workflow-card-kind">{data.category}</span><strong>{data.key}</strong><span className="block muted">{data.label}</span>
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
    try { return readWorkflowDocument(value, profiles).dag; }
    catch { return null; }
  }, [value, profiles]);
  const [query, setQuery] = useState("");
  const [flow, setFlow] = useState<ReactFlowInstance<ServiceNode> | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const canvasRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !flow) return;
    let timer: ReturnType<typeof setTimeout>;
    const observer = new ResizeObserver(() => {
      clearTimeout(timer);
      // Let ReactFlow's own ResizeObserver commit the new canvas dimensions first.
      timer = setTimeout(() => void flow.fitView({ padding: 0.2 }), 100);
    });
    observer.observe(canvas);
    return () => { observer.disconnect(); clearTimeout(timer); };
  }, [flow]);
  const [pendingImport, setPendingImport] = useState<ReturnType<typeof readWorkflowDocument> | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [positions, setPositions] = useState<GraphPositions>({});
  const [measurements, setMeasurements] = useState<Record<string, { width: number; height: number }>>({});
  const [error, setError] = useState("");
  const [source, setSource] = useState(""); const [target, setTarget] = useState("");
  const nodes: ServiceNode[] = (dag?.tasks || []).map((task, index) => {
    const profile = profiles.find(p => p.id === task.serviceProfileVersionId);
    return { id: task.key, type: "service", selected: selected === task.key,
      measured: Object.hasOwn(measurements, task.key) ? measurements[task.key] : undefined,
      position: Object.hasOwn(positions, task.key) ? positions[task.key] : { x: (index % 3) * 270 + 25, y: Math.floor(index / 3) * 170 + 25 },
      data: { key: task.key, label: profile ? `${profile.key} · ${profile.version}` : task.serviceProfileVersionId,
        inputs: ports(profile, "inputs"), outputs: ports(profile, "outputs"), category: category(profile) } };
  });
  const current = dag?.tasks.find(task => task.key === selected);
  function commit(next: Dag) { onApply(stringify(next, null, 2) || ""); setError(""); }
  function connect(connection: Connection) {
    if (!dag) return;
    const { source: fromTask, target: toTask, sourceHandle: fromPort, targetHandle: toPort } = connection;
    if (disabled) return;
    try { commit(connectTasks(dag, profiles, fromTask, fromPort || "", toTask, toPort || "")); }
    catch (e) { setError(e instanceof Error ? e.message : "연결을 확인하세요."); }
  }
  function category(profile: Profile | undefined) {
    return !ports(profile, "inputs").length ? "데이터 입력" : !ports(profile, "outputs").length ? "결과 출력" : "처리 모듈";
  }
  function add(profileId: string, position?: { x: number; y: number }) {
    if (disabled || !dag || !profiles.some(p => p.id === profileId)) return;
    if (dag.tasks.length >= 128) { setError("작업은 128개까지 추가할 수 있습니다."); return; }
    let index = dag.tasks.length + 1; while (dag.tasks.some(t => t.key === `task-${index}`)) index++;
    const key = `task-${index}`;
    const box = canvasRef.current?.getBoundingClientRect();
    const center = box && flow?.screenToFlowPosition({ x: box.x + box.width / 2 - 100, y: box.y + box.height / 2 - 70 });
    const nextPosition = { ...(position || center || { x: 48, y: 56 }) };
    if (!position) while (nodes.some(n => Math.abs(n.position.x - nextPosition.x) < 30 && Math.abs(n.position.y - nextPosition.y) < 30)) { nextPosition.x += 32; nextPosition.y += 32; }
    setPositions(previous => ({ ...previous, [key]: nextPosition }));
    commit({ ...dag, tasks: [...dag.tasks, { key, serviceProfileVersionId: profileId, parameters: {} }] }); setSelected(key);
  }
  function applyImport(document: ReturnType<typeof readWorkflowDocument>) {
    commit(document.dag); setPositions(document.positions); setSelected(null); setPendingImport(null);
    setTimeout(() => void flow?.fitView({ padding: 0.2 }), 50);
  }
  function download() {
    if (!dag) return;
    const url = URL.createObjectURL(new Blob([stringify({ format: "edgeai.workflow-editor/v1", dag, positions: Object.fromEntries(nodes.map(n => [n.id, n.position])) }, null, 2) || ""], { type: "application/json" }));
    const a = document.createElement("a"); a.href = url; a.download = "workflow.json"; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function remove(key: string) {
    if (!dag) return;
    commit({ tasks: dag.tasks.filter(t => t.key !== key), dependencies: dag.dependencies.filter(e => e.fromTask !== key && e.toTask !== key) });
    setSelected(null);
  }
  return <div className="workflow-editor" aria-label="워크플로 시각 편집기">
    <div className="workflow-studio-toolbar"><div><strong>서비스 흐름</strong><span className="muted">작업 {nodes.length} · 연결 {dag?.dependencies.length || 0}</span></div><div className="toolbar">
      <input ref={fileInput} type="file" accept=".json,application/json" aria-label="워크플로 파일" hidden disabled={disabled} onChange={async e => {
        const file = e.target.files?.[0]; e.target.value = ""; if (!file || disabled) return;
        try {
          if (file.size > 1_000_000) throw new Error("워크플로 파일은 1MB 이하여야 합니다.");
          const imported = readWorkflowDocument(await file.text(), profiles);
          if (dag?.tasks.length) setPendingImport(imported); else applyImport(imported);
          setError("");
        } catch (e) { setError(e instanceof Error ? e.message : "워크플로 파일을 읽지 못했습니다."); }
      }} />
      <button type="button" disabled={disabled} onClick={() => fileInput.current?.click()}>파일 열기</button>
      <button type="button" disabled={!dag} onClick={download}>파일 저장</button>
      <button type="button" disabled={disabled || !nodes.length} onClick={() => { if (dag) { setPositions(arrangeWorkflow(dag)); setTimeout(() => void flow?.fitView({ padding: 0.2 }), 50); } }}>자동 정렬</button>
    </div></div>
    {pendingImport && <div className="workflow-import-confirm" role="alert"><p>현재 편집 중인 흐름을 파일의 작업 {pendingImport.dag.tasks.length}개로 바꿉니다.</p><button type="button" disabled={disabled} onClick={() => applyImport(pendingImport)}>파일로 교체</button><button type="button" onClick={() => setPendingImport(null)}>취소</button></div>}
    {!dag && <p className="error" role="alert">DAG JSON의 tasks와 dependencies를 확인하세요.</p>}
    {error && <p role="alert" className="error">{error}</p>}
    <div className="workflow-studio">
      <aside className="workflow-library" aria-label="실행 모듈 라이브러리"><h3>실행 모듈</h3>
        <label className="workflow-library-search">모듈 검색<input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="이름으로 검색" /></label>
        <p className="hint">끌어 놓거나 + 버튼으로 추가하세요.</p>
        {["데이터 입력", "처리 모듈", "결과 출력"].map(group => {
          const items = profiles.filter(p => category(p) === group && `${p.key} ${p.version}`.toLowerCase().includes(query.toLowerCase()));
          return items.length > 0 && <div className="workflow-library-group" key={group}><h4>{group}</h4>{items.map(p => <div key={p.id} className="workflow-library-item" draggable={!disabled && !!dag} onDragStart={e => { e.dataTransfer.setData("application/edgeai-service", p.id); e.dataTransfer.effectAllowed = "copy"; }}>
            <div><strong>{p.key}</strong><small>{p.version} · 입력 {ports(p, "inputs").length} / 출력 {ports(p, "outputs").length}</small></div><button type="button" disabled={disabled || !dag} aria-label={`${p.key} ${p.version} 추가`} onClick={() => add(p.id)}>+</button>
          </div>)}</div>;
        })}
        {!profiles.length && <p className="empty">등록된 실행 모듈이 없습니다.</p>}
        {!!profiles.length && !profiles.some(p => `${p.key} ${p.version}`.toLowerCase().includes(query.toLowerCase())) && <p className="empty">검색 결과가 없습니다.</p>}
        <a className="button-link" href="/profiles?kind=SERVICE">실행 모듈 등록</a>
      </aside>
      <div ref={canvasRef} className="workflow-canvas" onDragOver={e => { e.preventDefault(); e.dataTransfer.dropEffect = "copy"; }} onDrop={e => { e.preventDefault(); if (flow) add(e.dataTransfer.getData("application/edgeai-service"), flow.screenToFlowPosition({ x: e.clientX, y: e.clientY })); }}>
        <ReactFlow<ServiceNode> nodes={nodes} nodeTypes={nodeTypes} onInit={setFlow}
          edges={(dag?.dependencies || []).map((e, i) => ({ id: String(i), source: e.fromTask, target: e.toTask, sourceHandle: e.fromPort, targetHandle: e.toPort, label: e.mode, markerEnd: { type: MarkerType.ArrowClosed } }))}
          onNodesChange={changes => {
            for (const change of changes) {
              if (change.type === "dimensions" && change.dimensions) setMeasurements(previous => previous[change.id]?.width === change.dimensions!.width && previous[change.id]?.height === change.dimensions!.height ? previous : { ...previous, [change.id]: change.dimensions! });
              if (!disabled && change.type === "position" && change.position) setPositions(previous => ({ ...previous, [change.id]: change.position! }));
            }
          }}
          onConnect={connect} onNodeClick={(_, node) => setSelected(node.id)} onPaneClick={() => setSelected(null)} nodesDraggable={!disabled} nodesConnectable={!disabled}
          deleteKeyCode={null} fitView minZoom={0.15} maxZoom={1.5}><Background gap={20} /><Controls showInteractive={false} /><MiniMap pannable zoomable /></ReactFlow>
        {!nodes.length && <div className="workflow-canvas-empty"><strong>모듈을 놓아 서비스를 구성하세요</strong><p>데이터 입력 → 처리 → 결과 출력</p></div>}
      </div>
      <aside className="workflow-inspector" aria-label="선택 작업 설정"><h3>작업 설정</h3>
        <label>편집할 작업<select value={selected || ""} onChange={e => setSelected(e.target.value)}><option value="">작업 선택</option>{nodes.map(n => <option key={n.id}>{n.id}</option>)}</select></label>
        {!current && <p className="empty">캔버스에서 작업을 선택하면 설정을 편집할 수 있습니다.</p>}
    {current && <fieldset className="publish-fields" disabled={disabled} key={current.key}><legend>{current.key} · ServiceTask</legend>
      <label>작업 키<input defaultValue={current.key} maxLength={100} onBlur={e => {
        const key = e.target.value.trim(); if (key === current.key || !dag) return;
        if (!/^[a-z][a-z0-9]*([._-][a-z0-9]+)*$/.test(key) || dag.tasks.some(t => t.key === key)) { setError("중복되지 않는 영문 소문자 작업 키를 입력하세요."); e.target.value = current.key; return; }
        setPositions(previous => ({ ...previous, [key]: previous[current.key] || nodes.find(n => n.id === current.key)!.position }));
        commit({ tasks: dag.tasks.map(t => t.key === current.key ? { ...t, key } : t), dependencies: dag.dependencies.map(edge => ({ ...edge, fromTask: edge.fromTask === current.key ? key : edge.fromTask, toTask: edge.toTask === current.key ? key : edge.toTask })) }); setSelected(key);
      }} /></label>
      <label>작업 매개변수 JSON<textarea rows={3} defaultValue={stringify(current.parameters, null, 2) || "{}"} onBlur={e => {
        try { const parameters = parse(e.target.value); if (!parameters || typeof parameters !== "object" || Array.isArray(parameters)) throw new Error();
          if (dag) commit({ ...dag, tasks: dag.tasks.map(t => t.key === current.key ? { ...t, parameters: parameters as Record<string, unknown> } : t) });
        } catch { setError("작업 매개변수는 JSON 객체여야 합니다. 수정한 값이 반영되지 않았습니다."); }
      }} /></label>
      <button type="button" onClick={() => remove(current.key)}>선택 작업 삭제</button>
    </fieldset>}
      </aside>
    </div>
    {!!nodes.length && <details className="workflow-connections"><summary>포트 연결 · 키보드로 편집</summary><fieldset className="publish-fields" disabled={disabled}><legend>포트 연결</legend><div className="form-row">
      <label>출력 포트<select value={source} onChange={e => setSource(e.target.value)}><option value="">출력 선택</option>{nodes.flatMap(n => n.data.outputs.map(port => <option key={`${n.id}/${port}`} value={JSON.stringify([n.id, port])}>{n.id} · {port}</option>))}</select></label>
      <label>입력 포트<select value={target} onChange={e => setTarget(e.target.value)}><option value="">입력 선택</option>{nodes.flatMap(n => n.data.inputs.map(port => <option key={`${n.id}/${port}`} value={JSON.stringify([n.id, port])}>{n.id} · {port}</option>))}</select></label>
    </div><button type="button" disabled={!source || !target} onClick={() => { const [sourceId, sourceHandle] = JSON.parse(source); const [targetId, targetHandle] = JSON.parse(target); connect({ source: sourceId, sourceHandle, target: targetId, targetHandle }); }}>포트 연결 추가</button></fieldset></details>}
    {!!dag?.dependencies.length && <ul className="history-list">{dag.dependencies.map((edge, index) => <li key={index}>
      {edge.fromTask}.{edge.fromPort} → {edge.toTask}.{edge.toPort} · {edge.mode} <button type="button" disabled={disabled} aria-label={`${edge.fromTask} → ${edge.toTask} 연결 삭제`} onClick={() => commit({ ...dag, dependencies: dag.dependencies.filter((_, i) => i !== index) })}>연결 삭제</button>
    </li>)}</ul>}
  </div>;
}
