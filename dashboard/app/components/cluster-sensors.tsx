"use client";
import { useRef, useState } from "react";
import type { InfrastructureSnapshot } from "../../lib/infrastructure";
import { StatusBadge } from "./status-badge";
import { SensorDetail } from "./sensor-detail";

export function ClusterSensors({ snapshot, failed, search = "" }: { snapshot: InfrastructureSnapshot | null; failed: boolean; search?: string }) {
  const [selected, setSelected] = useState("");
  const trigger = useRef<HTMLButtonElement>(null);
  if (failed || snapshot?.sensorsStatus === "UNAVAILABLE") return <p className="error" role="status">센서 목록을 불러오지 못했습니다. 다시 조회합니다.</p>;
  if (snapshot?.sensorsStatus === "DISABLED") return <p role="status">센서 조회 연결 대기</p>;
  if (!snapshot) return <p role="status">센서 목록을 불러오는 중…</p>;
  const sensors = snapshot.sensors.filter(s => `${s.name} ${s.nodeName} ${s.model} ${s.properties.join(" ")}`.toLowerCase().includes(search.toLowerCase()));
  return <>
    <div className="table-scroll"><table className="sensor-table"><caption className="sr-only">EdgeX 센서 목록</caption>
      <thead><tr><th>센서 · EdgeX 장치</th><th>연결된 엣지 디바이스</th><th>측정 항목</th><th>EdgeX 상태</th></tr></thead>
      <tbody>{sensors.map(sensor => <tr key={sensor.name}>
        <th scope="row"><button className="version-link" aria-expanded={selected === sensor.name} onClick={event => { trigger.current = event.currentTarget; setSelected(selected === sensor.name ? "" : sensor.name); }}>{sensor.name}</button><small className="block muted">{sensor.model}</small></th>
        <td data-label="연결된 엣지 디바이스">{sensor.nodeName || "연결 정보 없음"}<small className="block muted">{sensor.serviceName}</small></td><td data-label="측정 항목">{sensor.properties.join(" · ") || "—"}</td>
        <td data-label="EdgeX 상태"><StatusBadge state={sensor.operatingState === "DOWN" ? "OFFLINE" : "REGISTERED"} label={sensor.operatingState} /><small className="block muted">{sensor.adminState}</small></td>
      </tr>)}</tbody>
    </table></div>
    {!sensors.length && <p className="empty">{snapshot.sensors.length ? "검색 조건에 맞는 센서가 없습니다." : "EdgeX에 등록된 센서가 없습니다."}</p>}
    {sensors.filter(sensor => sensor.name === selected).map(sensor => <SensorDetail key={sensor.name} sensor={sensor} onClose={() => { setSelected(""); trigger.current?.focus(); }} />)}
  </>;
}
