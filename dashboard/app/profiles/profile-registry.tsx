"use client";

import { parse, stringify, isLosslessNumber } from "lossless-json";
import { useRef, useState, type FormEvent } from "react";
import type { components } from "../../lib/api-schema";

import { ConnectionPanel } from "../components/connection-panel";
import { registryItems } from "../../lib/registry";
import { validateVDTemplate } from "../../lib/vd-profile";
import { ProfileFields } from "./profile-fields";
import { HardwareProfileFields } from "./hardware-profile-fields";
import { EmptyState } from "../components/empty-state";

type Profile = components["schemas"]["ProfileVersion"];
type Kind = Profile["kind"];
type Page = { items: Profile[]; nextOffset: number | null };
const kinds: Kind[] = ["DEVICE", "SERVICE", "VD"];
const names: Record<Kind, string> = { DEVICE: "장치", SERVICE: "실행 모듈", VD: "가상 디바이스 템플릿" };

export function ProfileRegistry({ workflows = false }: { workflows?: boolean }) {
  const createPanel = useRef<HTMLDetailsElement>(null);
  function openCreatePanel() {
    if (!createPanel.current) return;
    createPanel.current.open = true;
    createPanel.current.scrollIntoView({ block: "start" });
    createPanel.current.querySelector<HTMLInputElement>('input[name="key"]')?.focus({ preventScroll: true });
  }
  const [auth, setAuth] = useState("");
  const [csrf, setCsrf] = useState("");
  const [kind, setKind] = useState<Kind>("DEVICE");
  const [result, setResult] = useState<Page | null>(null);
  const [offset, setOffset] = useState(0);
  const [filter, setFilter] = useState("");
  const [detail, setDetail] = useState<Profile | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [nodes, setNodes] = useState<components["schemas"]["ExecutionNode"][]>([]);
  const [profileChoices, setProfileChoices] = useState<Profile[]>([]);
  const [specText, setSpecText] = useState("");

  async function api(path: string, authorization = auth, init?: RequestInit) {
    const response = await fetch(`/api/control-plane/${path}`, {
      ...init, cache: "no-store", headers: { ...(authorization.startsWith("Basic ") ? { Authorization: authorization } : {}), ...init?.headers },
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      const message = response.status === 401 ? "API 연결을 확인하세요."
        : response.status === 403 ? "연결이 만료되었습니다. 화면을 새로고침하세요."
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
  function connect() {
    void run(async () => {
      const authorization = "dashboard";
      const token = await (await api("csrf", authorization)).json();
      const selectedKind = new URLSearchParams(window.location.search).get("kind");
      const initialKind = kinds.includes(selectedKind as Kind) ? selectedKind as Kind : kind;
      setKind(initialKind); await load(initialKind, 0, "", authorization);
      setProfileChoices((await Promise.all(["SERVICE", "DEVICE"].map(value => registryItems<Profile>(`profiles/${value}`, path => api(path, authorization))))).flat());
      setNodes((await (await api("nodes?limit=100", authorization)).json()).items);
      setAuth(authorization); setCsrf(token.token);
    });
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
      if (kind === "VD") {
        const issue = validateVDTemplate(spec, profileChoices);
        if (issue) throw new Error(issue);
      }
      const response = await api(`profiles/${kind}`, auth, {
        method: "POST", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf },
        body: stringify({ key: data.get("key"), version: data.get("version"), spec }),
      });
      const saved = parse(await response.text()) as Profile;
      setDetail(saved);
      if (kind !== "VD") setProfileChoices(previous => [...previous.filter(p => p.id !== saved.id), saved]);
      setNotice(response.status === 201 ? "새 버전을 발행했습니다." : "같은 내용의 버전이 이미 발행되어 있습니다.");
      await load(kind, 0, filter);
    });
  }

  function remove(profile: Profile) {
    void run(async () => {
      await api(`profiles/${profile.kind}/${profile.key}/versions/${profile.version}`, auth,
        { method: "DELETE", headers: { "X-CSRF-TOKEN": csrf } });
      if (detail?.id === profile.id) setDetail(null);
      setProfileChoices(previous => previous.filter(value => value.id !== profile.id));
      setDeleting(null);
      await load(kind, result?.items.length === 1 ? Math.max(0, offset - 20) : offset, filter);
      setNotice(`${profile.key} · ${profile.version} 버전을 삭제했습니다.`);
    });
  }

  return <>
    <ConnectionPanel connected={!!auth} busy={busy} onConnect={connect} />
    <div aria-live="polite" aria-atomic="true">{notice && <p className="notice">{notice}</p>}</div>
    {error && <p role="alert" className="error">{error}</p>}
    {auth && <>
      <div className="registry-workspace"><section aria-labelledby="registry-title" aria-busy={busy}>
        <div className="toolbar"><div><h2 id="registry-title">발행된 규격과 템플릿</h2><p className="hint">변경 사항은 새 버전으로 등록하고, 사용하지 않는 버전은 삭제할 수 있습니다.</p></div><button className="primary" onClick={openCreatePanel}>새 버전 등록</button></div>
        <fieldset className="kind-picker" disabled={busy}><legend>Profile 종류</legend>
          {kinds.map(value => <label key={value}><input type="radio" name="kind" checked={kind === value} onChange={() => {
            setKind(value); setSpecText(""); setDetail(null); setResult(null); setFilter(""); setDeleting(null);
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
        {result && result.items.length === 0 && <EmptyState title={filter ? "일치하는 Profile이 없습니다" : "첫 프로필을 등록하세요"} description={filter ? "정확한 프로필 키를 확인하거나 검색어를 비워 전체 목록을 조회하세요." : "장치와 서비스가 사용할 규격을 정의하는 첫 단계입니다."} action={!filter && <button onClick={openCreatePanel}>{names[kind]} Profile 등록</button>} />}
        {result && result.items.length > 0 && <div className="table-scroll"><table className="registry-table">
          <caption className="sr-only">{names[kind]} Profile 버전 목록</caption>
          <thead><tr><th>키 / 버전</th><th>내용 digest</th><th>발행일</th><th>관리</th></tr></thead>
          <tbody>{result.items.map(profile => <tr key={profile.id} data-selected={detail?.id === profile.id}>
            <td><button className="version-link" disabled={busy} onClick={() => { setDetail(null); void run(async () => {
              setDetail(parse(await (await api(`profiles/${kind}/${profile.key}/versions/${profile.version}`)).text()) as Profile);
            }); }}>{profile.key} <span className="mono">{profile.version}</span></button></td>
            <td className="mono">{profile.digest.slice(0, 19)}…</td><td>{profile.createdAt.slice(0, 10)}</td>
            <td>{deleting === profile.id ? <div><span>이 버전을 삭제할까요?</span><div className="toolbar"><button disabled={busy} onClick={() => remove(profile)}>삭제 확인</button><button disabled={busy} onClick={() => setDeleting(null)}>취소</button></div></div>
              : <button disabled={busy} aria-label={`${profile.key} ${profile.version} 삭제`} onClick={() => setDeleting(profile.id)}>삭제</button>}</td>
          </tr>)}</tbody>
        </table></div>}
        {result && <div className="pagination"><button disabled={busy || offset === 0} onClick={() => void run(() => load(kind, Math.max(0, offset - 20), filter))}>이전</button>
          <span>{Math.floor(offset / 20) + 1} 페이지</span>
          <button disabled={busy || result.nextOffset === null} onClick={() => void run(() => load(kind, result.nextOffset!, filter))}>다음</button></div>}
      </section>
      {detail && <section className="panel" aria-labelledby="detail-title">
        <div className="toolbar"><h2 id="detail-title">{detail.key} · {detail.version}</h2><button onClick={() => setDetail(null)}>상세 닫기</button></div>
        <p className="hint">발행됨 · 이 버전의 규격은 변경되지 않습니다.</p>
        <div className="toolbar"><button onClick={() => { setSpecText(stringify(detail.spec, null, 2) || ""); openCreatePanel(); }}>이 규격으로 새 버전 작성</button>{(detail.kind !== "SERVICE" || workflows) && <a href={detail.kind === "DEVICE" ? `/devices?profile=${detail.id}` : detail.kind === "VD" ? `/virtual-devices?profile=${detail.id}` : `/workflows?service=${detail.id}`}>{detail.kind === "DEVICE" ? "장치 등록" : detail.kind === "VD" ? "가상 장치 등록" : "서비스 실행 구성"} →</a>}</div>
        <p className="mono digest">버전 ID {detail.id}</p><p className="mono digest">{detail.digest}</p><pre aria-label="발행된 JSON 규격">{stringify(detail.spec, null, 2)}</pre>
      </section>}
      </div><details className="create-panel" ref={createPanel}>
        <summary><h2 id="publish-title">{names[kind]} Profile 새 버전 등록</h2></summary>
        <form onSubmit={publish}>
          <fieldset disabled={busy} className="publish-fields">
            <div className="form-row"><label>Profile 키<input name="key" required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" placeholder="temperature-sensor" /></label>
              <label>버전<input name="version" required maxLength={32} pattern="(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)" placeholder="1.0.0" /></label></div>
            <ProfileFields kind={kind} value={specText} onChange={setSpecText} profiles={profileChoices} />
            {kind === "SERVICE" && <HardwareProfileFields nodes={nodes} value={specText} onChange={setSpecText} />}
            <label>JSON 규격<textarea aria-label="JSON 규격" name="spec" rows={9} spellCheck={false} required value={specText} onChange={e => setSpecText(e.target.value)} placeholder={'{\n  "protocol": "mqtt"\n}'} aria-describedby="spec-help" /></label>
            <p className="hint" id="spec-help">비어 있지 않은 JSON 객체 · 최대 64 KiB. 비밀번호나 토큰을 규격에 넣지 마세요. 등록은 실행 호환성을 보장하지 않습니다.</p>
            <button className="primary" type="submit">{busy ? "처리 중…" : "버전 발행"}</button>
          </fieldset>
        </form>
      </details>
    </>}
  </>;
}
