import { PageHeading } from "../components/page-heading";
import { DeviceInventory } from "./device-inventory";

export const dynamic = "force-dynamic";
export default async function Devices({ searchParams }: { searchParams: Promise<{ view?: string; create?: string }> }) {
  const { view, create } = await searchParams;
  const initialView = ["servers", "edge", "sensors", "virtual", "unknown"].includes(view ?? "") ? view : "servers";

  return <main id="main-content" className="management-page devices-page">
    <PageHeading eyebrow="INFRASTRUCTURE / DEVICES" title="인프라 관리" description="서버·엣지 디바이스·센서·가상디바이스의 개수를 확인하고 관리합니다." />
    <DeviceInventory initialView={initialView} initialCreateOpen={view === "virtual" && create === "1"} />
  </main>;
}
