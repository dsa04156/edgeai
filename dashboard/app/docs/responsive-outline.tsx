"use client";

import { useEffect, useRef, type ReactNode } from "react";

export function ResponsiveOutline({ label, children }: { label: string; children: ReactNode }) {
  const ref = useRef<HTMLDetailsElement>(null);
  useEffect(() => {
    const small = window.matchMedia("(max-width: 700px)");
    const update = () => { if (ref.current) ref.current.open = !small.matches; };
    update();
    small.addEventListener("change", update);
    return () => small.removeEventListener("change", update);
  }, []);
  return <details open ref={ref}><summary>{label}</summary>{children}</details>;
}
