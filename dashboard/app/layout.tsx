import type { Metadata } from "next";
import { ConsoleShell } from "./components/console-shell";
import { workflowsEnabled } from "../lib/features";
import "./globals.css";
import "./nexus-theme.css";

export const metadata: Metadata = {
  title: "EdgeAI · Control Plane",
  description: "장치, 가상 장치, 워크플로와 실행 결과를 관리하는 EdgeAI 운영 콘솔",
};
export const dynamic = "force-dynamic";
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html lang="ko"><body><ConsoleShell workflows={workflowsEnabled()}>{children}</ConsoleShell></body></html>;
}
