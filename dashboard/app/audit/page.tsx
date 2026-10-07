import { PageHeading } from "../components/page-heading";
import { AuditHistory } from "./audit-history";

export const dynamic = "force-dynamic";
export default function Audit() {

  return <main id="main-content" className="management-page audit-page">
    <PageHeading eyebrow="MANAGEMENT / AUDIT" title="감사 기록" description="관리 요청의 처리 결과와 변경 이력을 확인합니다." />
    <AuditHistory />
  </main>;
}
