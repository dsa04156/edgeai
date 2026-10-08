"use client";

import { useRef, useState, type FormEvent } from "react";
import { isLosslessNumber, parse, stringify } from "lossless-json";
import type { components } from "../../lib/api-schema";
import { nextProfileVersion } from "../../lib/profile-version";
import { validateVDTemplate } from "../../lib/vd-profile";
import { ProfileFields } from "../profiles/profile-fields";
import { HardwareProfileFields } from "../profiles/hardware-profile-fields";

type Schema = components["schemas"];
type Profile = Schema["ProfileVersion"];
type Feedback = { state: "pending" | "error" | "success"; message: string };
type Write = (path: string, body: object) => Promise<Response>;

function InlineProfileForm({ kind, profiles, nodes, initial, write, onRegistered, onBusy }: {
  kind: Profile["kind"]; profiles: Profile[]; nodes: Schema["ExecutionNode"][]; initial?: Profile;
  write: Write; onRegistered: (value: Profile) => void; onBusy: (value: boolean) => void;
}) {
  const [key, setKey] = useState(initial?.key || "");
  const [version, setVersion] = useState(initial ? nextProfileVersion(initial, profiles) : "1.0.0");
  const [specText, setSpecText] = useState(initial ? stringify(initial.spec, null, 2) || "" : "");
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const result = useRef<HTMLParagraphElement>(null);
  const busy = feedback?.state === "pending";
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy) return;
    setFeedback({ state: "pending", message: "버전을 발행하고 있습니다…" }); onBusy(true);
    try {
      let spec: unknown;
      try { spec = parse(specText); } catch { throw new Error("JSON 문법을 확인하세요."); }
      if (!spec || isLosslessNumber(spec) || typeof spec !== "object" || Array.isArray(spec) || !Object.keys(spec).length)
        throw new Error("규격은 비어 있지 않은 JSON 객체여야 합니다.");
      if (kind === "VD") { const issue = validateVDTemplate(spec, profiles); if (issue) throw new Error(issue); }
      const response = await write(`profiles/${kind}`, { key, version, spec });
      const saved = parse(await response.text()) as Profile;
      onRegistered(saved);
      setFeedback({ state: "success", message: `${saved.key} · ${saved.version} ${response.status === 201 ? "버전을 발행했습니다." : "버전이 이미 등록되어 있습니다."}` });
    } catch (error) {
      setFeedback({ state: "error", message: error instanceof Error ? error.message : "등록하지 못했습니다." });
    } finally { onBusy(false); }
    requestAnimationFrame(() => { result.current?.focus({ preventScroll: true }); result.current?.scrollIntoView({ block: "center" }); });
  }
  return <form aria-label={`${kind} 바로 등록`} onSubmit={submit} onInvalid={() => setFeedback({ state: "error", message: "필수 입력과 형식을 확인하세요. 버전은 1.0.0 형식으로 입력합니다." })}>
    <fieldset disabled={busy} className="publish-fields">
      {initial && <p className="hint">{initial.key} · {initial.version}의 내용을 새 버전으로 수정합니다. 기존 버전과 연결은 유지됩니다.</p>}
      <div className="form-row"><label>Profile 키<input required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" value={key} onChange={event => setKey(event.target.value)} /></label>
        <label>버전<input required maxLength={32} pattern="(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)" value={version} onChange={event => setVersion(event.target.value)} /></label></div>
      <ProfileFields kind={kind} value={specText} onChange={setSpecText} profiles={profiles} />
      {kind === "SERVICE" && <HardwareProfileFields nodes={nodes} value={specText} onChange={setSpecText} />}
      <label>JSON 규격<textarea required rows={8} spellCheck={false} value={specText} onChange={event => setSpecText(event.target.value)} /></label>
      {feedback && <p className={feedback.state === "error" ? "error" : feedback.state === "success" ? "notice" : "hint"} role={feedback.state === "error" ? "alert" : "status"} ref={result} tabIndex={-1}>{feedback.message}</p>}
      <button className="primary">{busy ? "발행 중…" : initial ? "수정 버전 발행" : "버전 발행"}</button>
    </fieldset>
  </form>;
}

export function VDPrerequisites({ profiles, nodes, template, write, onProfileRegistered, onBusy }: {
  profiles: Profile[]; nodes: Schema["ExecutionNode"][]; template?: Profile; write: Write;
  onProfileRegistered: (value: Profile) => void; onBusy: (value: boolean) => void;
}) {
  const [draft, setDraft] = useState<Profile | undefined>();
  const profileForm = (kind: Profile["kind"], initial?: Profile) => <InlineProfileForm key={initial?.id || kind} kind={kind} profiles={profiles} nodes={nodes} initial={initial} write={write} onRegistered={onProfileRegistered} onBusy={onBusy} />;
  return <div className="vd-prerequisites">
    <p className="hint">실행 모듈과 VD 템플릿을 준비한 뒤 센서 디바이스를 원본으로 선택합니다. <a href="/devices?view=sensors">센서 디바이스 관리 →</a></p>
    <details className="vd-prerequisite"><summary>실행 모듈 SERVICE 등록</summary>{profileForm("SERVICE")}</details>
    <details className="vd-prerequisite"><summary>VD 템플릿 등록·수정</summary>
      <div className="toolbar"><button type="button" onClick={() => setDraft(undefined)}>새 템플릿 작성</button>{template && <button type="button" onClick={() => setDraft(template)}>선택 템플릿 새 버전으로 수정</button>}</div>
      {profileForm("VD", draft)}
    </details>
  </div>;
}
