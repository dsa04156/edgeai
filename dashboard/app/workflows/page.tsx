import { SectionNav } from "../components/section-nav";
import { notFound } from "next/navigation";
import { workflowsEnabled } from "../../lib/features";
import Link from "next/link";
import { WorkflowConsole } from "./workflow-console";

export const dynamic = "force-dynamic";

export default function Workflows() {
  if (!workflowsEnabled()) notFound();
  return <main>
    <header><Link className="brand" href="/">EdgeAI</Link><span className="stage">Edge AI Platform</span></header>
    <SectionNav current="/workflows" />
    <section><p className="eyebrow">WORKFLOW & EXECUTION</p><h1>작업의 순서를 정의하고 실행을 추적합니다.</h1>
      <p className="intro">서비스를 DAG로 연결해 버전을 발행하고, 실행 요청과 작업별 시도를 관리합니다. 발행된 정의와 실행 이력은 별도로 보존합니다.</p></section>
    <WorkflowConsole view="builder" />
    <footer>서버의 실행 설정에 따라 작업을 배치합니다. 작업 상세에서 실제 상태와 검증·확정된 결과를 확인하세요.</footer>
  </main>;
}
