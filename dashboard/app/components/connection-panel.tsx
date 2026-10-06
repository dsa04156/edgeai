"use client";
import { useEffect, useRef } from "react";

/** Connect once on entry; credentials stay on the Dashboard server. */
export function ConnectionPanel({ connected, busy, onConnect }: {
  connected: boolean; busy: boolean; onConnect: () => void;
}) {
  const initialConnect = useRef(onConnect);
  useEffect(() => { initialConnect.current(); }, []);
  if (connected) return null;
  return <div className="toolbar" role="status">
    <p>{busy ? "데이터를 불러오는 중…" : "서버 연결을 확인하세요."}</p>
    {!busy && <button onClick={onConnect}>다시 불러오기</button>}
  </div>;
}
