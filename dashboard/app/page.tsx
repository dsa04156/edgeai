import Link from "next/link";
import { getControlPlaneHealth } from "../lib/health";

export const dynamic = "force-dynamic";

export default async function Home() {
  const health = await getControlPlaneHealth();
  return (
    <main>
      <header><span className="brand">EdgeAI</span><span className="stage">M4 · Runtime / Result</span></header>
      <section aria-labelledby="title">
        <p className="eyebrow">EDGE AI CONTROL PLANE</p>
        <h1 id="title">실행부터 결과까지 확인합니다.</h1>
        <p className="intro">장치와 AI 서비스를 연결하고, 실행부터 결과까지 관리하는 플랫폼입니다. Profile과 장치를 등록하고, 워크플로 실행·작업 상태·검증된 결과를 확인할 수 있습니다.</p>
      </section>
      <section className="flow" aria-label="플랫폼 실행 흐름">
        <span>Profile</span><span aria-hidden="true">→</span><span>Device</span><span aria-hidden="true">→</span><span>Workflow</span><span aria-hidden="true">→</span><span>Runtime</span><span aria-hidden="true">→</span><span>Result</span>
      </section>
      <section className="status" aria-labelledby="scope">
        <h2 id="scope">현재 구현 범위</h2>
        <dl>
          <div><dt>Dashboard</dt><dd>장치·워크플로·결과 관리</dd></div>
          <div><dt>Control Plane · PostgreSQL</dt><dd>{health.status === "UP" ? "연결 확인됨" : "연결 대기 중"}</dd></div>
          <div><dt>Profile</dt><dd><Link href="/profiles">Profile 관리 →</Link></dd></div>
          <div><dt>Device · Node</dt><dd><Link href="/devices">장치·노드 관리 →</Link></dd></div><div><dt>Workflow · Run</dt><dd><Link href="/workflows">워크플로·실행 관리 →</Link></dd></div>
          <div><dt>Virtual Device</dt><dd><Link href="/virtual-devices">가상 장치 관리 →</Link></dd></div>
          <div><dt>Audit</dt><dd><Link href="/audit">감사 기록 →</Link></dd></div>
          <div><dt>실장비 실행</dt><dd className="muted">아직 검증하지 않음</dd></div>
        </dl>
      </section>
      <footer>이 화면은 클러스터의 실시간 상태를 표시하지 않습니다.</footer>
    </main>
  );
}
