import { PageHeading } from "../components/page-heading";
import { ProfileRegistry } from "./profile-registry";
import { workflowsEnabled } from "../../lib/features";

export const dynamic = "force-dynamic";
export default function Profiles() {

  return <main id="main-content" className="management-page profiles-page">
    <PageHeading eyebrow="MANAGEMENT / PROFILES" title="프로필 관리" description="DEVICE 장치 규격, SERVICE 실행 모듈, VD 템플릿을 발행합니다. 발행된 버전은 유지하고 변경은 새 버전으로 등록합니다." />
    <ProfileRegistry workflows={workflowsEnabled()} />
  </main>;
}
