"use client";

import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";

const groups = [
  { label: "워크스페이스", items: [["/", "운영 현황", "overview"]] },
  { label: "인프라", items: [["/devices", "인프라 관리", "nodes"], ["/virtual-devices", "가상 장치", "virtual"]] },
  { label: "실행 관리", workflows: true, items: [["/workflows", "서비스 워크플로", "workflow"], ["/runs", "실행 · 작업 · 결과", "runs"]] },
  { label: "관리", items: [["/profiles", "프로필 관리", "profile"], ["/audit", "감사 기록", "audit"], ["/docs", "프로젝트 문서", "docs"]] },
];

export function ConsoleIcon({ name }: { name: string }) {
  if (name === "docs") return <svg className="console-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><path d="M4 3h11l5 5v13H4zM14 3v6h6M8 13h8M8 17h6" /></svg>;
  const icons: Record<string, string> = { overview: "diagram-3", nodes: "hdd-network", virtual: "cpu", workflow: "diagram-3", runs: "activity", profile: "diagram-3", audit: "bell", search: "search" };
  return <Image className="console-icon" src={`/icons/${icons[name] || "diagram-3"}.svg`} width={18} height={18} alt="" />;
}

export function ConsoleShell({ children, workflows }: { children: React.ReactNode; workflows: boolean }) {
  const path = usePathname();
  const isDocs = path === "/docs" || path.startsWith("/docs/");
  const [menu, setMenu] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const [health, setHealth] = useState<"loading" | "up" | "down">("loading");
  useEffect(() => {
    if (isDocs) return;
    let stopped = false;
    const controller = new AbortController();
    async function check() {
      try {
        const response = await fetch("/api/health", { cache: "no-store", signal: controller.signal });
        const value = await response.json();
        if (!stopped) setHealth(response.ok && value.status === "UP" ? "up" : "down");
      } catch { if (!stopped) setHealth("down"); }
    }
    void check(); const timer = setInterval(check, 30000);
    return () => { stopped = true; controller.abort(); clearInterval(timer); };
  }, [isDocs]);
  const active = groups.flatMap(group => group.items).find(([href]) => href === path);
  if (isDocs) return <>{children}</>;
  return <div className="console-shell">
    <a className="skip-link" href="#main-content">본문으로 이동</a>
    <aside className="console-sidebar">
      <div className="sidebar-brand"><Link href="/" className="brand" aria-label="EdgeAI 운영 현황"><span className="brand-mark" aria-hidden="true">E</span><span>EdgeAI<small>혼합 디바이스 통합 운영</small></span></Link><button ref={menuButton} className="mobile-menu" aria-expanded={menu} aria-controls="console-navigation" onClick={() => setMenu(!menu)}>메뉴 {menu ? "닫기" : "열기"}</button></div>
      <div className="workspace-label"><span className="workspace-symbol" aria-hidden="true">E</span><div>Edge AI Platform<small>장비 · 서비스 운영</small></div><span className="workspace-version">v0.1</span></div>
      <nav id="console-navigation" className={menu ? "console-nav is-open" : "console-nav"} aria-label="관리 메뉴" onKeyDown={event => { if (event.key === "Escape") { setMenu(false); menuButton.current?.focus(); } }}>
        {groups.filter(group => workflows || !group.workflows).map(group => <div className="nav-group" key={group.label}><p>{group.label}</p>{group.items.map(([href, title, icon]) => <Link href={href} key={href} aria-current={path === href ? "page" : undefined} onClick={() => setMenu(false)}><ConsoleIcon name={icon} /><span>{title}</span>{path === href && <span className="nav-active-dot" aria-hidden="true" />}</Link>)}</div>)}
      </nav>
      <div className="sidebar-foot"><span className="small-label">CONTROL PLANE</span><span className={`connection-state ${health}`}><i />{health === "up" ? "API · DB 연결됨" : health === "down" ? "연결 확인 필요" : "연결 확인 중"}</span><small>상태 확인 · 30초 간격</small></div>
    </aside>
    <div className="console-body"><header className="console-topbar">{path === "/" ? <form action="/devices" method="get" className="console-search" role="search"><ConsoleIcon name="search" /><input type="hidden" name="view" value="nodes" /><input type="search" name="search" aria-label="노드 검색" placeholder="노드 이름, GPU, NPU 검색" /><button type="submit">검색</button></form> : <div className="breadcrumb"><span className="breadcrumb-root">통합 플랫폼</span><span aria-hidden="true">/</span><strong>{active?.[1] || "관리 화면"}</strong></div>}<span className={`connection-state topbar-connection ${health}`} role="status"><i aria-hidden="true" />{health === "up" ? "서버 연결됨" : health === "down" ? "연결 확인 필요" : "연결 확인 중"}</span></header>{children}<footer className="console-footer"><span>EdgeAI / 혼합 디바이스 제어·관리 플랫폼</span><span>{workflows ? "Profile → Device → Workflow → Run → Result" : "Profile → Device → Virtual Device"}</span></footer></div>
  </div>;
}
