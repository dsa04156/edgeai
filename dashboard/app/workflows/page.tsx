import Link from "next/link";
import { WorkflowConsole } from "./workflow-console";

export default function Workflows() {
  return <main>
    <header><Link className="brand" href="/">EdgeAI</Link><span className="stage">M3 · Workflow / Run</span></header>
    <nav className="section-nav" aria-label="관리 메뉴"><Link href="/profiles">Profile 관리</Link><Link href="/devices">장치·노드 관리</Link><Link href="/workflows" aria-current="page">워크플로·실행 관리</Link></nav>
    <section><p className="eyebrow">WORKFLOW & EXECUTION</p><h1>작업의 순서를 정의하고 실행을 추적합니다.</h1>
      <p className="intro">서비스를 DAG로 연결해 버전을 발행하고, 실행 요청과 작업별 시도를 관리합니다. 발행된 정의와 실행 이력은 별도로 보존합니다.</p></section>
    <WorkflowConsole />
    <footer>현재는 실행 요청 저장·조회·취소를 제공합니다. 실제 Kubernetes 작업 실행과 검증된 결과 저장은 다음 단계입니다.</footer>
  </main>;
}
