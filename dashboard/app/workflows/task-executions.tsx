import type { components } from "../../lib/api-schema";

export type TaskExecutions = components["schemas"]["TaskExecutions"];

export function TaskExecutionFields({ tasks, value, onChange }: {
  tasks: components["schemas"]["DagTask"][];
  value: TaskExecutions;
  onChange: (value: TaskExecutions) => void;
}) {
  function select(key: string, mode: string) {
    const next = { ...value };
    if (mode === "DEFAULT") delete next[key];
    else next[key] = mode === "NODE" ? { mode, nodeId: "" } : { mode: "AUTO" };
    onChange(next);
  }
  return <fieldset className="publish-fields">
    <legend>작업별 실행 위치</legend>
    <p className="hint">기본 정책을 따르거나 작업마다 최초 위치를 지정하세요. 대기 중인 작업에도 적용됩니다. 노드 전환 뒤 재시도하면 전환된 위치를 유지합니다.</p>
    {tasks.map(task => {
      const placement = value[task.key];
      return <div className="form-row task-placement-row" key={task.key}>
        <label>{task.key} 실행 위치<select value={placement?.mode || "DEFAULT"} onChange={e => select(task.key, e.target.value)}>
          <option value="DEFAULT">기본 실행 정책 따름</option>
          <option value="AUTO">자동 선택 (AUTO)</option>
          <option value="NODE">노드 지정 (NODE)</option>
        </select></label>
        {placement?.mode === "NODE" && <label>{task.key} 노드 ID<input required maxLength={36}
          pattern="[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"
          list="workflow-execution-nodes" value={placement.nodeId} placeholder="관측된 Node UUID"
          onChange={e => onChange({ ...value, [task.key]: { mode: "NODE", nodeId: e.target.value } })} /></label>}
      </div>;
    })}
  </fieldset>;
}
