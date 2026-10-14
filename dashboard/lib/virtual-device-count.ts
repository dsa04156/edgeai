"use client";

import { useEffect, useState } from "react";
import type { components } from "./api-schema";
import { registryItems } from "./registry";

export function useVirtualDeviceCount(refresh = 0) {
  const [state, setState] = useState<{ count: number | null; at: number; pending: boolean }>({ count: null, at: 0, pending: true });
  const [now, setNow] = useState(0);
  useEffect(() => {
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout>;
    const clock = setInterval(() => setNow(Date.now()), 5000);
    async function load() {
      try {
        const items = await registryItems<components["schemas"]["VirtualDevice"]>("virtual-devices", async path => {
          const response = await fetch(`/api/control-plane/${path}`, { cache: "no-store", signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]) });
          if (!response.ok) throw new Error("가상디바이스 목록 조회 실패");
          return response;
        });
        if (!controller.signal.aborted) { const at = Date.now(); setNow(at); setState({ count: items.filter(item => item.state === "REGISTERED").length, at, pending: false }); }
      } catch { if (!controller.signal.aborted) setState({ count: null, at: 0, pending: false }); }
      finally { if (!controller.signal.aborted) timer = setTimeout(load, 10000); }
    }
    void load(); return () => { controller.abort(); clearTimeout(timer); clearInterval(clock); };
  }, [refresh]);
  return { count: now - state.at <= 60000 ? state.count : null, pending: state.pending };
}
