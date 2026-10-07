import { PageHeading } from "../components/page-heading";
import { DeviceInventory } from "./device-inventory";

export const dynamic = "force-dynamic";
export default function Devices() {

  return <main id="main-content" className="management-page devices-page">
    <PageHeading eyebrow="INFRASTRUCTURE / DEVICES" title="서버 · 디바이스 · 센서" description="엣지 AI 서버와 현장 디바이스, 연결된 센서를 확인합니다." />
    <DeviceInventory />
  </main>;
}
