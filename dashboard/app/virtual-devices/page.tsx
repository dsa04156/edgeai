import { VirtualDeviceRegistry } from "./virtual-device-registry";

export const dynamic = "force-dynamic";
export default async function VirtualDevices({ searchParams }: { searchParams: Promise<{ create?: string }> }) {
  const { create } = await searchParams;

  return <main id="main-content" className="management-page virtual-devices-page">
    <VirtualDeviceRegistry initiallyOpen={create === "1"} />
  </main>;
}
