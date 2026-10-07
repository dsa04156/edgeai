import Link from "next/link";
import "./docs.css";

export default function DocsLayout({ children }: { children: React.ReactNode }) {
  return <div className="docs-site">
    <a className="skip-link" href="#main-content">본문으로 이동</a>
    <header className="docs-topbar"><Link className="docs-brand" href="/docs"><span aria-hidden="true">E</span>Edge AI Docs</Link>
      <nav aria-label="문서 사이트"><Link href="/docs#documents">문서 찾기</Link><Link href="/">대시보드 ↗</Link></nav>
    </header>
    {children}
    <footer className="docs-footer">EdgeAI 프로젝트 문서 <span>현재 상태는 「현재 개발 상태」를 기준으로 확인하세요.</span></footer>
  </div>;
}
