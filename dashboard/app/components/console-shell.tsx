"use client";

import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";

const groups = [
  { label: "워크스페이스", items: [["/", "운영 현황", "overview"]] },
  { label: "인프라", items: [["/devices", "인프라 관리", "nodes"]] },
  { label: "실행 관리", workflows: true, items: [["/workflows", "서비스 워크플로", "workflow"], ["/runs", "실행 · 작업 · 결과", "runs"]] },
  { label: "관리", items: [["/docs", "프로젝트 문서", "docs"]] },
];

export function ConsoleIcon({ name }: { name: string }) {
  if (name === "docs") return <svg className="console-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><path d="M4 3h11l5 5v13H4zM14 3v6h6M8 13h8M8 17h6" /></svg>;
  const icons: Record<string, string> = { overview: "diagram-3", nodes: "hdd-network", sensor: "cpu", workflow: "diagram-3", runs: "activity", profile: "diagram-3", storage: "hdd-network", audit: "bell", search: "search" };
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
  const pageName = active?.[1] || ({ "/profiles": "실행 모듈 관리", "/data-store": "데이터 저장소", "/audit": "감사 기록" }[path] ?? "관리 화면");
  if (isDocs) return <>{children}</>;
  return <div className="console-shell">
    <a className="skip-link" href="#main-content">본문으로 이동</a>
    <aside className="console-sidebar">
      <div className="sidebar-brand"><Link href="/" className="brand" aria-label="EdgeAI 운영 현황"><span className="brand-mark" aria-hidden="true">E</span><span className="brand-name">EdgeAI<small>통합 운영 콘솔</small></span></Link><button ref={menuButton} className="mobile-menu" aria-expanded={menu} aria-controls="console-navigation" onClick={() => setMenu(!menu)}>메뉴 {menu ? "닫기" : "열기"}</button></div>
      <nav id="console-navigation" className={menu ? "console-nav is-open" : "console-nav"} aria-label="관리 메뉴" onKeyDown={event => { if (event.key === "Escape") { setMenu(false); menuButton.current?.focus(); } }}>
        {groups.filter(group => workflows || !group.workflows).map(group => <div className="nav-group" key={group.label}><p>{group.label}</p>{group.items.map(([href, title, icon]) => <Link href={href} title={title} key={href} aria-current={path === href ? "page" : undefined} onClick={() => setMenu(false)}><ConsoleIcon name={icon} /><span>{title}</span></Link>)}</div>)}
      </nav>
    </aside>
    <div className="console-body"><header className="console-topbar"><div className="breadcrumb"><span className="breadcrumb-root">EdgeAI</span><span aria-hidden="true">/</span><strong>{pageName}</strong></div>{path === "/" && <form action="/devices" method="get" className="console-search" role="search"><ConsoleIcon name="search" /><input type="hidden" name="view" value="nodes" /><input type="search" name="search" aria-label="노드 검색" placeholder="노드 이름, GPU, NPU 검색" /><button type="submit">검색</button></form>}<span className={`connection-state topbar-connection ${health}`} role="status"><i aria-hidden="true" />{health === "up" ? "서버 연결됨" : health === "down" ? "연결 확인 필요" : "연결 확인 중"}</span></header>{children}<footer className="console-footer"><span>EdgeAI · 통합 운영 콘솔</span><span>{workflows ? "실행 모듈 → 워크플로 → 실행 → 결과" : "센서 디바이스 관리"}</span></footer></div>
  </div>;
}
