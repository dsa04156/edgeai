import { notFound } from "next/navigation";
import Link from "next/link";
import { workflowsEnabled } from "../../lib/features";
import { SectionNav } from "../components/section-nav";
import { WorkflowConsole } from "../workflows/workflow-console";
export const dynamic = "force-dynamic";
export default function Runs() {
  if (!workflowsEnabled()) notFound();
  return <main><header><Link className="brand" href="/">EdgeAI</Link><span className="stage">Execution</span></header>
    <SectionNav current="/runs" /><section><p className="eyebrow">RUN · TASK · PLACEMENT</p><h1>배치부터 결과까지 추적합니다.</h1>
      <p className="intro">실행을 선택하면 작업별 상태, 실제 실행 노드와 Pod, 확정된 결과를 확인할 수 있습니다.</p><Link href="/workflows">Workflow Builder에서 새 실행 구성 →</Link></section>
    <WorkflowConsole view="runs" />
  </main>;
}
