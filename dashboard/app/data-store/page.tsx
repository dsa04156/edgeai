import { PageHeading } from "../components/page-heading";
import { DataStoreBrowser } from "./data-store-browser";

export const dynamic = "force-dynamic";
export default function DataStorePage() {
  return <main id="main-content" className="management-page">
    <PageHeading eyebrow="DATA / HISTORY" title="데이터 저장소" description="센서 원본을 측정 시각과 수집 설정별로 확인합니다. 가상디바이스 처리 결과는 장치별 저장 정책에 따라 보관합니다." />
    <DataStoreBrowser />
  </main>;
}
