import Link from "next/link";
import { WorkflowConsole } from "./workflow-console";

export default function Workflows() {
  return <main>
    <header><Link className="brand" href="/">EdgeAI</Link><span className="stage">M4 · Runtime / Result</span></header>
    <nav className="section-nav" aria-label="관리 메뉴"><Link href="/profiles">Profile 관리</Link><Link href="/devices">장치·노드 관리</Link><Link href="/workflows" aria-current="page">워크플로·실행 관리</Link><Link href="/virtual-devices">가상 장치 관리</Link></nav>
    <section><p className="eyebrow">WORKFLOW & EXECUTION</p><h1>작업의 순서를 정의하고 실행을 추적합니다.</h1>
      <p className="intro">서비스를 DAG로 연결해 버전을 발행하고, 실행 요청과 작업별 시도를 관리합니다. 발행된 정의와 실행 이력은 별도로 보존합니다.</p></section>
    <WorkflowConsole />
    <footer>서버의 실행 설정에 따라 작업을 배치합니다. 작업 상세에서 실제 상태와 검증·확정된 결과를 확인하세요.</footer>
  </main>;
}
