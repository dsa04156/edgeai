import Link from "next/link";
import { workflowsEnabled } from "../../lib/features";
export function SectionNav({ current }: { current: string }) {
  const links = [["/profiles", "Profile"], ["/devices", "DeviceManager"], ["/virtual-devices", "Virtual Device"],
    ...(workflowsEnabled() ? [["/workflows", "Workflow Builder"], ["/runs", "Run · Task · Placement"]] : []), ["/audit", "감사 기록"]];
  return <nav className="section-nav" aria-label="관리 메뉴">{links.map(([href, name]) => <Link key={href} href={href} aria-current={href === current ? "page" : undefined}>{name}</Link>)}</nav>;
}
