import Link from "next/link";
export default function DocumentNotFound() {
  return <main id="main-content" className="docs-home docs-empty"><h1>문서를 찾을 수 없습니다.</h1><p>이동했거나 삭제된 문서입니다. 목록에서 다시 찾아보세요.</p><Link href="/docs">문서 홈으로</Link></main>;
}
