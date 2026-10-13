import { PageHeading } from "../components/page-heading";
import { ProfileRegistry } from "./profile-registry";
import { workflowsEnabled } from "../../lib/features";

export const dynamic = "force-dynamic";
export default function Profiles() {

  return <main id="main-content" className="management-page profiles-page">
    <PageHeading eyebrow="MANAGEMENT / MODULES" title="실행 모듈 관리" description="워크플로에서 실행할 프로그램의 이미지·명령·입출력·CPU·메모리 설정을 저장합니다. 예를 들어 온도 평균 계산이나 AI 추론 프로그램을 등록한 뒤 워크플로에서 선택해 실행합니다." />
    <ProfileRegistry workflows={workflowsEnabled()} />
  </main>;
}
