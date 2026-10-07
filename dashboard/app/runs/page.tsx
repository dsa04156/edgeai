import { PageHeading } from "../components/page-heading";
import { WorkflowConsole } from "./../workflows/workflow-console";
import { notFound } from "next/navigation";
import { workflowsEnabled } from "../../lib/features";

export const dynamic = "force-dynamic";
export default function Runs() {
  if (!workflowsEnabled()) notFound();
  return <main id="main-content" className="management-page runs-page">
    <PageHeading eyebrow="ORCHESTRATION / EXECUTIONS" title="실행 · 작업 · 결과" description="실행 요청부터 작업 배치와 결과까지 추적합니다." />
    <WorkflowConsole view="runs" />
  </main>;
}
