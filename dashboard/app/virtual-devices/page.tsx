import { PageHeading } from "../components/page-heading";
import { workflowsEnabled } from "../../lib/features";
import { VirtualDeviceRegistry } from "./virtual-device-registry";

export const dynamic = "force-dynamic";
export default function VirtualDevices() {

  return <main id="main-content" className="management-page virtual-devices-page">
    <PageHeading eyebrow="INFRASTRUCTURE / VIRTUAL DEVICES" title="가상 디바이스" description="센서와 처리 기능을 선택해 가상 장치를 등록합니다. 원본 연결과 실행 위치를 교체해도 VD ID는 유지됩니다." />
    <VirtualDeviceRegistry workflows={workflowsEnabled()} />
  </main>;
}
