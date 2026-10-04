import Link from "next/link";
import { AuditHistory } from "./audit-history";

export default function Audit() {
  return <main>
    <header><Link className="brand" href="/">EdgeAI</Link><span className="stage">M9 · 감사 기록</span></header>
    <nav className="section-nav" aria-label="관리 메뉴"><Link href="/profiles">Profile 관리</Link><Link href="/devices">장치·노드 관리</Link><Link href="/workflows">워크플로·실행 관리</Link><Link href="/virtual-devices">가상 장치 관리</Link><Link href="/audit" aria-current="page">감사 기록</Link></nav>
    <section><p className="eyebrow">MANAGEMENT AUDIT</p><h1>요청의 주체와 응답을 확인합니다.</h1>
      <p className="intro">관리 변경 요청과 접근 거절을 확인합니다. 결과가 기록되지 않은 요청은 성공이나 실패로 추정하지 않고, 원래 장치·실행 상태와 함께 확인하세요.</p></section>
    <AuditHistory />
    <footer>HTTP 응답은 비동기 작업의 최종 성공을 뜻하지 않습니다. 내부 장치·Runner 요청과 관리 API 밖의 변경은 이 목록에 포함되지 않습니다.</footer>
  </main>;
}
