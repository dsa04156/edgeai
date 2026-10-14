import { redirect } from "next/navigation";

export const dynamic = "force-dynamic";
export default async function VirtualDevices({ searchParams }: { searchParams: Promise<{ create?: string }> }) {
  const { create } = await searchParams;

  redirect(`/devices?view=virtual${create === "1" ? "&create=1" : ""}`);
}
