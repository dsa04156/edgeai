import { PageHeading } from "../components/page-heading";
import PlatformService from "./platform-service/App";
import { WorkflowConsole } from "./workflow-console";
import { notFound } from "next/navigation";
import { workflowsEnabled } from "../../lib/features";
import Link from "next/link";

export const dynamic = "force-dynamic";
export default async function Workflows({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  if (!workflowsEnabled()) notFound();
  const query = await searchParams;
  const modules = query.mode !== "dds" || !!query.service || !!query.vd;
  return <main id="main-content" className="management-page workflows-page">
    <PageHeading eyebrow="ORCHESTRATION / BUILDER" title="서비스 워크플로" description={modules ? "SERVICE 모듈로 DAG를 발행하고, 실행 위치를 정해 Run · Task · 결과를 관리합니다." : "DDS 워크플로를 구성하고 Buildx · Gitea · Argo CD로 배포합니다."} />
    <nav className="section-nav" aria-label="워크플로 경로"><Link href="/workflows" aria-current={modules ? "page" : undefined}>DAG 서비스 실행</Link><Link href="/workflows?mode=dds" aria-current={!modules ? "page" : undefined}>DDS · GitOps 배포</Link></nav>
    {modules ? <WorkflowConsole view="builder" /> : <PlatformService />}
  </main>;
}
