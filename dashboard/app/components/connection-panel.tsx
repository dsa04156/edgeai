"use client";
import type { FormEvent } from "react";

export function ConnectionPanel({ connected, busy, onConnect, onDisconnect }: {
  connected: boolean; busy: boolean; onConnect: (event: FormEvent<HTMLFormElement>) => void; onDisconnect: () => void;
}) {
  return <section className="panel" aria-labelledby="connection-title">
    <h2 id="connection-title">개발 계정 연결</h2>
    {connected ? <div className="toolbar"><p>연결됨 · 이 탭을 새로고침하면 다시 연결해야 합니다.</p><button disabled={busy} onClick={onDisconnect}>연결 해제</button></div>
      : <form onSubmit={onConnect} className="login-form">
        <label>사용자 이름<input name="username" autoComplete="username" required maxLength={100} /></label>
        <label>비밀번호<input name="password" type="password" autoComplete="current-password" required maxLength={256} /></label>
        <button className="primary" disabled={busy}>{busy ? "연결 중…" : "연결"}</button>
        <p className="hint">현재 환경에 설정한 API 계정을 사용하세요. 계정 정보는 브라우저 저장소에 보관하지 않습니다.</p>
      </form>}
  </section>;
}
