"use client";

import { parse, stringify, isLosslessNumber } from "lossless-json";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { registryItems } from "../../lib/registry";
import type { components } from "../../lib/api-schema";

import { ConnectionPanel } from "../components/connection-panel";
import { nextProfileVersion, readProfilePage } from "../../lib/profile-version";
import { ProfileFields } from "./profile-fields";
import { HardwareProfileFields } from "./hardware-profile-fields";
import { EmptyState } from "../components/empty-state";

type Profile = components["schemas"]["ProfileVersion"];
type Page = { items: Profile[]; nextOffset: number | null };
type PublishFeedback = { state: "pending" | "error"; message: string }
  | { state: "success"; message: string; profileId: string; refreshError?: string };

export function ProfileRegistry({ workflows = false }: { workflows?: boolean }) {
  const createPanel = useRef<HTMLDetailsElement>(null);
  const publishForm = useRef<HTMLFormElement>(null);
  const publishError = useRef<HTMLParagraphElement>(null);
  const detailTitle = useRef<HTMLHeadingElement>(null);
  function openCreatePanel() {
    if (!createPanel.current) return;
    setPublishFeedback(null);
    setEditingProfile(null);
    createPanel.current.open = true;
    createPanel.current.scrollIntoView({ block: "start" });
    createPanel.current.querySelector<HTMLInputElement>('input[name="key"]')?.focus({ preventScroll: true });
  }
  const [auth, setAuth] = useState("");
  const [csrf, setCsrf] = useState("");
  const [result, setResult] = useState<Page | null>(null);
  const [offset, setOffset] = useState(0);
  const [filter, setFilter] = useState("");
  const [detail, setDetail] = useState<Profile | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [publishFeedback, setPublishFeedback] = useState<PublishFeedback | null>(null);
  const [profileChoices, setProfileChoices] = useState<Profile[]>([]);
  const [nodes, setNodes] = useState<components["schemas"]["ExecutionNode"][]>([]);
  const [specText, setSpecText] = useState("");
  const [editingProfile, setEditingProfile] = useState<Profile | null>(null);
  function editProfile(profile: Profile) {
    setSpecText(stringify(profile.spec, null, 2) || "");
    openCreatePanel();
    setEditingProfile(profile);
    const keyInput = publishForm.current?.querySelector<HTMLInputElement>('input[name="key"]');
    const versionInput = publishForm.current?.querySelector<HTMLInputElement>('input[name="version"]');
    const nextVersion = nextProfileVersion(profile, [...(result?.items || []), ...(profileChoices)]);
    if (keyInput) keyInput.value = profile.key;
    if (versionInput) { versionInput.value = nextVersion; versionInput.focus(); }
  }
  const publishedId = publishFeedback?.state === "success" ? publishFeedback.profileId : null;
  useEffect(() => {
    if (publishedId) {
      detailTitle.current?.focus({ preventScroll: true });
      detailTitle.current?.scrollIntoView({ block: "start" });
    }
  }, [publishedId]);
  useEffect(() => {
    if (publishFeedback?.state !== "error") return;
    const target = publishForm.current?.querySelector<HTMLElement>("input:invalid, textarea:invalid, select:invalid") || publishError.current;
    target?.focus({ preventScroll: true });
    target?.scrollIntoView({ block: "center" });
  }, [publishFeedback]);

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
  async function load(nextOffset: number, nextFilter: string, authorization = auth) {
    const query = new URLSearchParams({ limit: "20", offset: String(nextOffset) });
    if (nextFilter) query.set("key", nextFilter);
    const response = await api(`profiles/SERVICE?${query}`, authorization);
    setResult(await readProfilePage(response)); setOffset(nextOffset);
  }
  async function run(action: () => Promise<void>) {
    setBusy(true); setError(""); setNotice(""); setPublishFeedback(null);
    try { await action(); }
    catch (error) { setError(error instanceof Error ? error.message : "연결 상태를 확인하세요."); }
    finally { setBusy(false); }
  }
  function connect() {
    void run(async () => {
      const authorization = "dashboard";
      const token = await (await api("csrf", authorization)).json();
      await load(0, "", authorization);
      setProfileChoices(await registryItems<Profile>("profiles/SERVICE", path => api(path, authorization), readProfilePage));
      setNodes((await (await api("nodes?limit=100", authorization)).json()).items);
      setAuth(authorization); setCsrf(token.token);
    });
  }

  async function publish(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const data = new FormData(event.currentTarget);
    setBusy(true); setError(""); setNotice("");
    setPublishFeedback({ state: "pending", message: "버전을 발행하고 있습니다…" });
    try {
      let spec: unknown;
      try { spec = parse(String(data.get("spec"))); }
      catch { throw new Error("JSON 문법을 확인하세요. 속성 이름과 문자열은 큰따옴표로 감싸야 합니다."); }
      if (!spec || isLosslessNumber(spec) || Array.isArray(spec) || typeof spec !== "object" || Object.keys(spec).length === 0)
        throw new Error("규격은 비어 있지 않은 JSON 객체여야 합니다.");
      const response = await api("profiles/SERVICE", auth, {
        method: "POST", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf },
        body: stringify({ key: data.get("key"), version: data.get("version"), spec }),
      });
      const saved = parse(await response.text()) as Profile;
      setDetail(saved);
      setProfileChoices(current => [...current.filter(profile => profile.id !== saved.id), saved]);
      const feedback: PublishFeedback = {
        state: "success", profileId: saved.id,
        message: response.status === 201 ? "새 버전을 발행했습니다." : "같은 내용의 버전이 이미 발행되어 있습니다. 기존 버전을 표시합니다.",
      };
      setPublishFeedback(feedback);
      if (createPanel.current) createPanel.current.open = false;
      try { await load(0, filter); }
      catch (error) {
        setPublishFeedback({ ...feedback, refreshError: `버전은 발행됐지만 목록을 새로 불러오지 못했습니다. ${error instanceof Error ? error.message : "연결 상태를 확인하세요."}` });
      }
    } catch (error) {
      setPublishFeedback({ state: "error", message: error instanceof Error ? error.message : "연결 상태를 확인하세요." });
    } finally { setBusy(false); }
  }

  function remove(profile: Profile) {
    void run(async () => {
      await api(`profiles/${profile.kind}/${profile.key}/versions/${profile.version}`, auth,
        { method: "DELETE", headers: { "X-CSRF-TOKEN": csrf } });
      setProfileChoices(current => current.filter(value => value.id !== profile.id));
      if (detail?.id === profile.id) setDetail(null);
      setDeleting(null);
      await load(result?.items.length === 1 ? Math.max(0, offset - 20) : offset, filter);
      setNotice(`${profile.key} · ${profile.version} 버전을 삭제했습니다.`);
    });
  }

  return <>
    <ConnectionPanel connected={!!auth} busy={busy} onConnect={connect} />
    <div aria-live="polite" aria-atomic="true">{notice && <p className="notice">{notice}</p>}</div>
    {error && <p role="alert" className="error">{error}</p>}
    {auth && <>
      <div className="registry-workspace"><section aria-labelledby="registry-title" aria-busy={busy}>
        <div className="toolbar"><div><h2 id="registry-title">등록된 실행 모듈</h2><p className="hint">같은 프로그램 설정을 여러 워크플로에서 재사용합니다. 변경은 새 버전으로 등록합니다.</p></div><button className="primary" disabled={busy} onClick={openCreatePanel}>새 버전 등록</button></div>
        <form className="toolbar filter-form" onSubmit={event => {
          event.preventDefault(); const key = String(new FormData(event.currentTarget).get("filter")).trim();
          setFilter(key); setResult(null); void run(() => load(0, key));
        }}>
          <label>키로 찾기<input name="filter" placeholder="전체 또는 정확한 키" maxLength={100} /></label>
          <button disabled={busy}>조회</button>
        </form>
        {busy && <p role="status">불러오는 중…</p>}
        {result && result.items.length === 0 && <EmptyState title={filter ? "일치하는 실행 모듈이 없습니다" : "첫 실행 모듈을 등록하세요"} description={filter ? "정확한 모듈 키를 확인하거나 검색어를 비워 전체 목록을 조회하세요." : "워크플로에서 사용할 프로그램의 실행 설정을 등록하세요."} action={!filter && <button onClick={openCreatePanel}>실행 모듈 등록</button>} />}
        {result && result.items.length > 0 && <div className="table-scroll"><table className="registry-table">
          <caption className="sr-only">실행 모듈 버전 목록</caption>
          <thead><tr><th>키 / 버전</th><th>내용 digest</th><th>발행일</th><th>관리</th></tr></thead>
          <tbody>{result.items.map(profile => <tr key={profile.id} data-selected={detail?.id === profile.id}>
            <td><button className="version-link" disabled={busy} onClick={() => { setDetail(null); void run(async () => {
              setDetail(parse(await (await api(`profiles/SERVICE/${profile.key}/versions/${profile.version}`)).text()) as Profile);
            }); }}>{profile.key} <span className="mono">{profile.version}</span></button></td>
            <td className="mono">{profile.digest.slice(0, 19)}…</td><td>{profile.createdAt.slice(0, 10)}</td>
            <td>{deleting === profile.id ? <div><span>이 버전을 삭제할까요?</span><div className="toolbar"><button disabled={busy} onClick={() => remove(profile)}>삭제 확인</button><button disabled={busy} onClick={() => setDeleting(null)}>취소</button></div></div>
              : <div className="toolbar"><button disabled={busy} aria-label={`${profile.key} ${profile.version} 새 버전으로 수정`} onClick={() => editProfile(profile)}>수정</button><button disabled={busy} aria-label={`${profile.key} ${profile.version} 삭제`} onClick={() => setDeleting(profile.id)}>삭제</button></div>}</td>
          </tr>)}</tbody>
        </table></div>}
        {result && <div className="pagination"><button disabled={busy || offset === 0} onClick={() => void run(() => load(Math.max(0, offset - 20), filter))}>이전</button>
          <span>{Math.floor(offset / 20) + 1} 페이지</span>
          <button disabled={busy || result.nextOffset === null} onClick={() => void run(() => load(result.nextOffset!, filter))}>다음</button></div>}
      </section>
      {detail && <section className="panel" aria-labelledby="detail-title">
        <div className="toolbar"><h2 id="detail-title" className="profile-detail-title" ref={detailTitle} tabIndex={-1}>{detail.key} · {detail.version}</h2><button disabled={busy} onClick={() => setDetail(null)}>상세 닫기</button></div>
        {publishFeedback?.state === "success" && publishFeedback.profileId === detail.id && <>
          <p className="notice" role="status">{publishFeedback.message} <strong>{detail.key} · {detail.version}</strong></p>
          {publishFeedback.refreshError && <p className="error" role="alert">{publishFeedback.refreshError}</p>}
        </>}
        <p className="hint">발행됨 · 이 버전의 규격은 변경되지 않습니다.</p>
        <div className="toolbar"><button disabled={busy} onClick={() => editProfile(detail)}>새 버전으로 수정</button>{workflows && <a href={`/workflows?service=${detail.id}`}>워크플로에서 사용 →</a>}</div>
        <p className="mono digest">버전 ID {detail.id}</p><p className="mono digest">{detail.digest}</p><pre aria-label="발행된 JSON 규격">{stringify(detail.spec, null, 2)}</pre>
      </section>}
      </div><details className="create-panel" ref={createPanel}>
        <summary><h2 id="publish-title">실행 모듈 새 버전 등록</h2></summary>
        {editingProfile && <p className="notice">{editingProfile.key} · {editingProfile.version} 내용을 불러왔습니다. 변경 내용은 새 버전으로 발행하며 기존 버전과 사용 중인 연결은 유지됩니다.</p>}
        <form ref={publishForm} onSubmit={publish} aria-busy={publishFeedback?.state === "pending"} onInvalid={event => {
          const invalid = event.currentTarget.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>("input:invalid, textarea:invalid, select:invalid");
          if (event.target !== invalid || !invalid) return;
          const message = invalid.name === "key" ? "모듈 키를 입력하세요. 영문 소문자로 시작하고 소문자·숫자·점·밑줄·하이픈을 사용할 수 있습니다."
            : invalid.name === "version" ? "버전은 1.0.0 형식으로 입력하세요."
            : invalid.name === "spec" ? "JSON 규격을 입력하세요."
            : invalid.validationMessage;
          setPublishFeedback({ state: "error", message });
        }}>
          <fieldset disabled={busy} className="publish-fields">
            <div className="form-row"><label>모듈 키<input name="key" required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" placeholder="temperature-average" /></label>
              <label>버전<input name="version" required maxLength={32} pattern="(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)" placeholder="1.0.0" /></label></div>
            <ProfileFields value={specText} onChange={setSpecText} />
            <HardwareProfileFields nodes={nodes} value={specText} onChange={setSpecText} />
            <label>JSON 규격<textarea aria-label="JSON 규격" name="spec" rows={9} spellCheck={false} required value={specText} onChange={e => setSpecText(e.target.value)} placeholder={'{\n  "apiVersion": "edgeai/v1",\n  "image": "실행할 프로그램 이미지"\n}'} aria-describedby="spec-help" /></label>
            <p className="hint" id="spec-help">비어 있지 않은 JSON 객체 · 최대 64 KiB. 비밀번호나 토큰을 규격에 넣지 마세요. 등록은 실행 호환성을 보장하지 않습니다.</p>
            {publishFeedback?.state === "pending" && <p role="status">{publishFeedback.message}</p>}
            {publishFeedback?.state === "error" && <p className="error" role="alert" ref={publishError} tabIndex={-1}>{publishFeedback.message}</p>}
            <button className="primary" type="submit">{publishFeedback?.state === "pending" ? "발행 중…" : "버전 발행"}</button>
          </fieldset>
        </form>
      </details>
    </>}
  </>;
}
