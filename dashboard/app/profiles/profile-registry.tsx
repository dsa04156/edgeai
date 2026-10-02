"use client";

import { parse, stringify, isLosslessNumber } from "lossless-json";
import { useState, type FormEvent } from "react";
import type { components } from "../../lib/api-schema";

type Profile = components["schemas"]["ProfileVersion"];
type Kind = Profile["kind"];
type Page = { items: Profile[]; nextOffset: number | null };
const kinds: Kind[] = ["DEVICE", "SERVICE", "VD"];
const names: Record<Kind, string> = { DEVICE: "장치", SERVICE: "서비스", VD: "가상 디바이스" };

export function ProfileRegistry() {
  const [auth, setAuth] = useState("");
  const [csrf, setCsrf] = useState("");
  const [kind, setKind] = useState<Kind>("DEVICE");
  const [result, setResult] = useState<Page | null>(null);
  const [offset, setOffset] = useState(0);
  const [filter, setFilter] = useState("");
  const [detail, setDetail] = useState<Profile | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function api(path: string, authorization = auth, init?: RequestInit) {
    const response = await fetch(`/api/control-plane/${path}`, {
      ...init, cache: "no-store", headers: { Authorization: authorization, ...init?.headers },
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      const message = response.status === 401 ? "계정 정보를 확인하고 다시 연결하세요."
        : response.status === 403 ? "연결이 만료되었습니다. 연결을 해제한 뒤 다시 연결하세요."
        : body.message || "요청을 처리하지 못했습니다. 다시 시도하세요.";
      throw new Error(message);
    }
    return response;
  }
  async function load(nextKind: Kind, nextOffset: number, nextFilter: string, authorization = auth) {
    const query = new URLSearchParams({ limit: "20", offset: String(nextOffset) });
    if (nextFilter) query.set("key", nextFilter);
    const response = await api(`profiles/${nextKind}?${query}`, authorization);
    setResult(await response.json()); setOffset(nextOffset);
  }
  async function run(action: () => Promise<void>) {
    setBusy(true); setError(""); setNotice("");
    try { await action(); }
    catch (error) { setError(error instanceof Error ? error.message : "연결 상태를 확인하세요."); }
    finally { setBusy(false); }
  }
  function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    void run(async () => {
      const bytes = new TextEncoder().encode(`${data.get("username")}:${data.get("password")}`);
      const authorization = `Basic ${btoa(String.fromCharCode(...bytes))}`;
      const token = await (await api("csrf", authorization)).json();
      await load(kind, 0, "", authorization);
      setAuth(authorization); setCsrf(token.token); form.reset();
    });
  }
  function disconnect() {
    setAuth(""); setCsrf(""); setResult(null); setDetail(null); setError(""); setNotice(""); setFilter("");
  }
  function publish(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    void run(async () => {
      let spec: unknown;
      try { spec = parse(String(data.get("spec"))); }
      catch { throw new Error("JSON 문법을 확인하세요. 속성 이름과 문자열은 큰따옴표로 감싸야 합니다."); }
      if (!spec || isLosslessNumber(spec) || Array.isArray(spec) || typeof spec !== "object" || Object.keys(spec).length === 0)
        throw new Error("규격은 비어 있지 않은 JSON 객체여야 합니다.");
      const response = await api(`profiles/${kind}`, auth, {
        method: "POST", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf },
        body: stringify({ key: data.get("key"), version: data.get("version"), spec }),
      });
      const saved = parse(await response.text()) as Profile;
      setDetail(saved);
      setNotice(response.status === 201 ? "새 버전을 발행했습니다." : "같은 내용의 버전이 이미 발행되어 있습니다.");
      await load(kind, 0, filter);
    });
  }

  return <>
    <section className="panel" aria-labelledby="connection-title">
      <h2 id="connection-title">개발 계정 연결</h2>
      {auth ? <div className="toolbar"><p>연결됨 · 이 탭을 새로고침하면 다시 연결해야 합니다.</p><button disabled={busy} onClick={disconnect}>연결 해제</button></div>
        : <form onSubmit={login} className="login-form">
          <label>사용자 이름<input name="username" autoComplete="username" required maxLength={100} /></label>
          <label>비밀번호<input name="password" type="password" autoComplete="current-password" required maxLength={256} /></label>
          <button className="primary" disabled={busy}>{busy ? "연결 중…" : "연결"}</button>
          <p className="hint">로컬 개발 환경에 설정한 계정을 사용하세요. 입력한 계정 정보는 브라우저 저장소에 보관하지 않습니다.</p>
        </form>}
    </section>
    <div aria-live="polite" aria-atomic="true">{notice && <p className="notice">{notice}</p>}</div>
    {error && <p role="alert" className="error">{error}</p>}
    {auth && <>
      <section aria-labelledby="registry-title" aria-busy={busy}>
        <div className="toolbar"><h2 id="registry-title">발행된 Profile</h2><span className="muted">내용 수정 없이 버전으로 관리</span></div>
        <fieldset className="kind-picker" disabled={busy}><legend>Profile 종류</legend>
          {kinds.map(value => <label key={value}><input type="radio" name="kind" checked={kind === value} onChange={() => {
            setKind(value); setDetail(null); setResult(null); setFilter("");
            void run(() => load(value, 0, ""));
          }} />{names[value]} <span className="mono">{value}</span></label>)}
        </fieldset>
        <form className="toolbar filter-form" onSubmit={event => {
          event.preventDefault(); const key = String(new FormData(event.currentTarget).get("filter")).trim();
          setFilter(key); setResult(null); void run(() => load(kind, 0, key));
        }}>
          <label>키로 찾기<input key={kind} name="filter" placeholder="전체 또는 정확한 키" maxLength={100} /></label>
          <button disabled={busy}>조회</button>
        </form>
        {busy && <p role="status">불러오는 중…</p>}
        {result && result.items.length === 0 && <p className="empty">{filter ? "일치하는 Profile이 없습니다." : "아직 발행한 Profile이 없습니다. 아래에서 첫 버전을 등록하세요."}</p>}
        {result && result.items.length > 0 && <div className="table-scroll"><table>
          <caption className="sr-only">{names[kind]} Profile 버전 목록</caption>
          <thead><tr><th>키 / 버전</th><th>내용 digest</th><th>발행일</th></tr></thead>
          <tbody>{result.items.map(profile => <tr key={profile.id}>
            <td><button className="version-link" disabled={busy} onClick={() => { setDetail(null); void run(async () => {
              setDetail(parse(await (await api(`profiles/${kind}/${profile.key}/versions/${profile.version}`)).text()) as Profile);
            }); }}>{profile.key} <span className="mono">{profile.version}</span></button></td>
            <td className="mono">{profile.digest.slice(0, 19)}…</td><td>{profile.createdAt.slice(0, 10)}</td>
          </tr>)}</tbody>
        </table></div>}
        {result && <div className="pagination"><button disabled={busy || offset === 0} onClick={() => void run(() => load(kind, Math.max(0, offset - 20), filter))}>이전</button>
          <span>{Math.floor(offset / 20) + 1} 페이지</span>
          <button disabled={busy || result.nextOffset === null} onClick={() => void run(() => load(kind, result.nextOffset!, filter))}>다음</button></div>}
      </section>
      {detail && <section className="panel" aria-labelledby="detail-title">
        <div className="toolbar"><h2 id="detail-title">{detail.key} · {detail.version}</h2><span className="stage">발행됨 · 불변</span></div>
        <p className="mono digest">{detail.digest}</p><pre aria-label="발행된 JSON 규격">{stringify(detail.spec, null, 2)}</pre>
      </section>}
      <section className="panel" aria-labelledby="publish-title">
        <h2 id="publish-title">{names[kind]} Profile 새 버전 등록</h2>
        <form onSubmit={publish}>
          <fieldset disabled={busy} className="publish-fields">
            <div className="form-row"><label>Profile 키<input name="key" required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" placeholder="temperature-sensor" /></label>
              <label>버전<input name="version" required maxLength={32} pattern="(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)" placeholder="1.0.0" /></label></div>
            <label>JSON 규격<textarea name="spec" rows={9} spellCheck={false} required placeholder={'{\n  "protocol": "mqtt"\n}'} aria-describedby="spec-help" /></label>
            <p className="hint" id="spec-help">비어 있지 않은 JSON 객체 · 최대 64 KiB. 비밀번호나 토큰을 규격에 넣지 마세요. 등록은 실행 호환성을 보장하지 않습니다.</p>
            <button className="primary" type="submit">{busy ? "처리 중…" : "버전 발행"}</button>
          </fieldset>
        </form>
      </section>
    </>}
  </>;
}
