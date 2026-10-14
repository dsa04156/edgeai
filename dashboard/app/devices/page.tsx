import { DeviceInventory } from "./device-inventory";

export const dynamic = "force-dynamic";
export default async function Devices({ searchParams }: { searchParams: Promise<{ view?: string; create?: string }> }) {
  const { view, create } = await searchParams;
  const initialView = ["servers", "edge", "sensors", "virtual", "unknown"].includes(view ?? "") ? view : "servers";

  return <main id="main-content" className="management-page devices-page">
    <h1 className="sr-only">인프라 관리</h1>
    <DeviceInventory initialView={initialView} initialCreateOpen={view === "virtual" && create === "1"} />
  </main>;
}
