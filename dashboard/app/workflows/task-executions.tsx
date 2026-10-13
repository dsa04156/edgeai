import { hardwareLabel } from "../../lib/hardware";
import type { components } from "../../lib/api-schema";

export type TaskExecutions = components["schemas"]["TaskExecutions"];

export function TaskExecutionFields({ tasks, value, onChange, stream, nodes = [] }: {
  tasks: components["schemas"]["DagTask"][];
  value: TaskExecutions;
  onChange: (value: TaskExecutions) => void;
  stream: boolean;
  nodes?: components["schemas"]["ExecutionNode"][];
}) {
  function select(key: string, mode: string) {
    const next = { ...value };
    if (mode === "DEFAULT") delete next[key];
    else if (mode === "NODE") next[key] = { mode, nodeId: "" };
    else if (mode === "REMOTE") next[key] = { mode, providerKey: "reference" };
    else next[key] = { mode: "AUTO" };
    onChange(next);
  }
  return <fieldset className="publish-fields">
    <legend>작업별 실행 위치</legend>
    <p className="hint">기본 정책을 따르거나 작업마다 최초 위치를 지정하세요. 대기 중인 작업에도 적용됩니다. 노드 전환 뒤 재시도하면 전환된 위치를 유지합니다.</p>
    <p className="hint">{stream ? "STREAM은 자동 선택과 노드 지정을 함께 사용할 수 있습니다." : "BATCH는 자동 선택·노드 지정·Remote를 함께 사용할 수 있습니다."}</p>
    {tasks.map(task => {
      const placement = value[task.key];
      return <div className="form-row task-placement-row" key={task.key}>
        <label>{task.key} 실행 위치<select value={placement?.mode || "DEFAULT"} onChange={e => select(task.key, e.target.value)}>
          <option value="DEFAULT">기본 실행 정책 따름</option>
          <option value="AUTO">자동 선택 (AUTO)</option>
          <option value="NODE">노드 지정 (NODE)</option>
          <option value="REMOTE" disabled={stream}>원격 제공자 (REMOTE)</option>
        </select></label>
        {placement?.mode === "NODE" && <label>{task.key} 실행 노드<select required value={placement.nodeId}
          onChange={e => onChange({ ...value, [task.key]: { mode: "NODE", nodeId: e.target.value } })}>
          <option value="">노드 선택</option>{nodes.map(node => <option key={node.id} value={node.id} disabled={node.status !== "READY"}>
            {node.name} · {node.architecture} · {hardwareLabel(node)}
          </option>)}
        </select></label>}
        {placement?.mode === "REMOTE" && <label>{task.key} Remote 제공자 key<input required maxLength={63}
          pattern="[a-z][a-z0-9]*(-[a-z0-9]+)*" value={placement.providerKey}
          onChange={e => onChange({ ...value, [task.key]: { mode: "REMOTE", providerKey: e.target.value } })} /></label>}
      </div>;
    })}
  </fieldset>;
}
