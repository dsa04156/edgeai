import { PageHeading } from "../components/page-heading";
import { workflowsEnabled } from "../../lib/features";
import { VirtualDeviceRegistry } from "./virtual-device-registry";

export const dynamic = "force-dynamic";
export default function VirtualDevices() {

  return <main id="main-content" className="management-page virtual-devices-page">
    <PageHeading eyebrow="INFRASTRUCTURE / VIRTUAL DEVICES" title="가상 디바이스" description="템플릿으로 논리 장치를 등록하고 원본·실행 연결을 관리합니다. 연결과 실행 위치를 교체해도 VD ID는 유지됩니다." />
    <VirtualDeviceRegistry workflows={workflowsEnabled()} />
  </main>;
}
