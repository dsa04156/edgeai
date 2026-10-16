/* eslint-disable @next/next/no-img-element -- Native images display bounded, fresh object URLs without image caching. */
"use client";

import { useEffect, useRef, useState } from "react";

export function CameraPreview({ id, uid }: { id: string; uid: string }) {
  const [frame,setFrame]=useState<string|null>(null),[error,setError]=useState(""),[paused,setPaused]=useState(false),[at,setAt]=useState("");
  const current=useRef<string|null>(null);
  useEffect(()=>{
    if(paused)return;
    const controller=new AbortController();let timer:ReturnType<typeof setTimeout>;
    function clear(){if(current.current)URL.revokeObjectURL(current.current);current.current=null;setFrame(null);}
    async function load(){
      try{
        if(document.hidden)return;
        const response=await fetch(`/api/control-plane/managed-devices/${encodeURIComponent(id)}/preview?uid=${encodeURIComponent(uid)}`,{cache:"no-store",signal:controller.signal});
        if(!response.ok){const body=await response.json().catch(()=>({}));throw new Error(body.message||"새 카메라 영상을 받지 못했습니다.");}
        if(response.headers.get("content-type")!=="image/jpeg")throw new Error("카메라 영상 형식을 확인할 수 없습니다.");
        const blob=await response.blob();if(blob.size>4*1024*1024)throw new Error("영상 프레임 크기가 너무 큽니다.");
        if(controller.signal.aborted)return;
        const next=URL.createObjectURL(blob);if(current.current)URL.revokeObjectURL(current.current);current.current=next;
        setFrame(next);setError("");setAt(new Date().toLocaleTimeString("ko-KR"));
      }catch(e){if(!controller.signal.aborted){clear();setError(e instanceof Error?e.message:"영상을 조회할 수 없습니다.");}}
      finally{if(!controller.signal.aborted)timer=setTimeout(()=>void load(),1000);}
    }
    void load();return()=>{controller.abort();clearTimeout(timer);if(current.current)URL.revokeObjectURL(current.current);current.current=null;};
  },[id,uid,paused]);
  return <div className="camera-preview">
    <div className="toolbar"><span className="muted">RTSP 미리보기 · 1초마다 조회 · 음성 없음</span><button onClick={()=>setPaused(value=>!value)}>{paused?"미리보기 재개":"미리보기 일시정지"}</button></div>
    {error&&!paused&&<p role="alert" className="error">{error}</p>}
    <div className="camera-preview-frame">{!paused&&frame?<img src={frame} alt={`${id} 실시간 영상`} onError={()=>{setFrame(null);setError("영상 프레임을 표시할 수 없습니다.");}}/>:<p className="muted">{paused?"미리보기가 일시정지됐습니다.":error?"새 영상 수신 대기 중":"카메라 영상을 불러오는 중…"}</p>}</div>
    {frame&&!paused&&!error&&<p className="muted">조회 시각 {at}</p>}
  </div>;
}
