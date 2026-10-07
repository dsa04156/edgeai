"use client";
import { useState } from "react";
import { parse, stringify } from "lossless-json";
import type { components } from "../../lib/api-schema";
import { accelerators, acceleratorNames, hardwareLabel } from "../../lib/hardware";

export function HardwareProfileFields({ nodes, value, onChange }: {
  nodes: components["schemas"]["ExecutionNode"][];
  value: string;
  onChange: (value: string) => void;
}) {
  const [nodeId, setNodeId] = useState("");
  const [resource, setResource] = useState("");
  const [quantity, setQuantity] = useState(1);
  const [pinNode, setPinNode] = useState(false);
  const [error, setError] = useState("");
  const node = nodes.find(n => n.id === nodeId);
  function apply() {
    try {
      if (!node || node.status !== "READY") throw new Error("현재 준비된 실행 노드를 선택하세요.");
      const spec = parse(value) as Record<string, unknown>;
      if (!spec || typeof spec !== "object" || Array.isArray(spec) || !spec.image || !spec.command)
        throw new Error("먼저 실행할 서비스 이미지와 명령이 있는 SERVICE JSON 규격을 입력하세요.");
      if (resource && (!Number.isInteger(quantity) || quantity < 1 || quantity > Number(node.allocatable[resource] || 0)))
        throw new Error("관측된 자원 수량 안에서 요청량을 지정하세요.");
      const resources = spec.resources as { requests?: Record<string, string>; limits?: Record<string, string> } | undefined;
      const requests = { cpu: "100m", memory: "256Mi", ...resources?.requests };
      const limits = { cpu: "1", memory: "1Gi", ...resources?.limits };
      if (resource) { Object.assign(requests, { [resource]: String(quantity) }); Object.assign(limits, { [resource]: String(quantity) }); }
      const nodeSelector = { ...(spec.nodeSelector as Record<string, string> || {}), "kubernetes.io/arch": node.architecture };
      if (pinNode) Object.assign(nodeSelector, { "kubernetes.io/hostname": node.name });
      else delete (nodeSelector as Record<string, string>)["kubernetes.io/hostname"];
      const next = { ...spec, resources: { requests, limits },
        platform: { ...(spec.platform as object || {}), os: node.operatingSystem, architectures: [node.architecture] }, nodeSelector };
      onChange(stringify(next, null, 2) || ""); setError("");
    } catch (e) { setError(e instanceof Error ? e.message : "SERVICE JSON 규격을 확인하세요."); }
  }
  return <details className="hardware-profile"><summary>관측 장비로 실행 요구사항 설정</summary>
    <p className="hint">서비스 이미지의 아키텍처와 장비용 라이브러리를 확인한 뒤 자원을 선택하세요. 등록된 자원 총량이며 현재 여유량은 아닙니다.</p>
    <div className="form-row"><label>실행 노드<select aria-label="실행 노드" value={nodeId} onChange={e => { setNodeId(e.target.value); setResource(""); setQuantity(1); }}>
      <option value="">노드 선택</option>{nodes.map(n => <option key={n.id} value={n.id} disabled={n.status !== "READY"}>{n.name} · {n.architecture} · {hardwareLabel(n)}</option>)}
    </select></label><label>요청할 GPU·NPU<select aria-label="요청할 GPU·NPU" value={resource} onChange={e => setResource(e.target.value)}>
      <option value="">기존 자원 요청 유지</option>{node && accelerators(node).map(([key, count]) => <option key={key} value={key}>{acceleratorNames[key] || key} · 총 {count}</option>)}
    </select></label><label>요청 수량<input type="number" min={1} max={node && resource ? Number(node.allocatable[resource]) : 1} value={quantity} disabled={!resource} onChange={e => setQuantity(Number(e.target.value))} /></label></div>
    <label className="checkbox-row"><input type="checkbox" checked={pinNode} onChange={event => setPinNode(event.target.checked)} /> 이 노드로 실행 위치 제한</label>
    <p className="hint">선택하지 않으면 호환 자원·아키텍처만 반영하고 기존 hostname 제한을 해제합니다. 최종 배치는 kube-scheduler가 결정합니다. 실행 시 NODE 정책으로도 위치를 지정할 수 있습니다.</p>
    <button type="button" disabled={!node} onClick={apply}>실행 요구사항 반영</button>
    {error && <p className="error" role="alert">{error}</p>}
  </details>;
}
