import { SectionNav } from "../components/section-nav";
import Link from "next/link";
import { AuditHistory } from "./audit-history";

export const dynamic = "force-dynamic";

export default function Audit() {
  return <main>
    <header><Link className="brand" href="/">EdgeAI</Link><span className="stage">Edge AI Platform</span></header>
    <SectionNav current="/audit" />
    <section><p className="eyebrow">MANAGEMENT AUDIT</p><h1>요청의 주체와 응답을 확인합니다.</h1>
      <p className="intro">관리 변경 요청과 접근 거절을 확인합니다. 결과가 기록되지 않은 요청은 성공이나 실패로 추정하지 않고, 원래 장치·실행 상태와 함께 확인하세요.</p></section>
    <AuditHistory />
    <footer>HTTP 응답은 비동기 작업의 최종 성공을 뜻하지 않습니다. 내부 장치·Runner 요청과 관리 API 밖의 변경은 이 목록에 포함되지 않습니다.</footer>
  </main>;
}
