import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "EdgeAI · 개발 환경",
  description: "Edge AI Control Plane 개발 환경",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html lang="ko"><body>{children}</body></html>;
}
