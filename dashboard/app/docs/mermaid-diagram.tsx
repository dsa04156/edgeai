"use client";

import { useEffect, useId, useRef, useState } from "react";

export function MermaidDiagram({ source }: { source: string }) {
  const id = useId().replace(/[^a-zA-Z0-9]/g, "");
  const target = useRef<HTMLDivElement>(null);
  const [state, setState] = useState("loading");
  useEffect(() => {
    let cancelled = false;
    import("mermaid").then(async ({ default: mermaid }) => {
      mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme: "neutral", suppressErrorRendering: true });
      const { svg } = await mermaid.render(`docs-diagram-${id}`, source);
      if (!cancelled && target.current) { target.current.innerHTML = svg; setState("ready"); }
    }).catch(() => { if (!cancelled) setState("error"); });
    return () => { cancelled = true; };
  }, [id, source]);
  return <figure className="docs-diagram">
    <div ref={target} role="img" aria-label="문서 다이어그램" />
    {state === "loading" && <p role="status">다이어그램을 불러오는 중…</p>}
    {state === "error" && <p role="status">다이어그램을 표시할 수 없습니다. 아래 원문을 확인하세요.</p>}
    <details><summary>다이어그램 원문</summary><pre><code>{source}</code></pre></details>
  </figure>;
}
