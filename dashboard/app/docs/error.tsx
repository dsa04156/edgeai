"use client";
import Link from "next/link";
export default function DocsError({ reset }: { reset: () => void }) {
  return <main id="main-content" className="docs-home docs-empty"><h1>문서를 불러오지 못했습니다.</h1><p>문서 파일을 읽을 수 없습니다. 잠시 후 다시 시도하세요.</p><button onClick={reset}>다시 시도</button> <Link href="/">대시보드로</Link></main>;
}
