import { PageHeading } from "../components/page-heading";
import { VirtualDeviceRegistry } from "./virtual-device-registry";

export const dynamic = "force-dynamic";
export default async function VirtualDevices({ searchParams }: { searchParams: Promise<{ create?: string }> }) {
  const { create } = await searchParams;

  return <main id="main-content" className="management-page virtual-devices-page">
    <PageHeading eyebrow="INFRASTRUCTURE / VIRTUAL DEVICES" title="가상 디바이스" description="센서 입력과 계산을 선택해 가상 장치를 구성합니다. 연속 처리 결과는 항목별 최신값과 그래프로 확인합니다." />
    <VirtualDeviceRegistry initiallyOpen={create === "1"} />
  </main>;
}
