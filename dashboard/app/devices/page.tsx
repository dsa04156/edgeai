import Link from "next/link";
import { DeviceInventory } from "./device-inventory";

export default function Devices() {
  return <main>
    <header><Link className="brand" href="/">EdgeAI</Link><span className="stage">M2 · Device / Node</span></header>
    <nav className="section-nav" aria-label="관리 메뉴"><Link href="/profiles">Profile 관리</Link><Link href="/devices" aria-current="page">장치·노드 관리</Link><Link href="/workflows">워크플로·실행 관리</Link><Link href="/virtual-devices">가상 장치 관리</Link></nav>
    <section><p className="eyebrow">DEVICE INVENTORY</p><h1>장치의 연결과 변화를 확인합니다.</h1>
      <p className="intro">장치 규격을 선택해 등록하고, 실행 노드·재접속 세션·최근 상태 보고를 확인합니다. 실제 데이터와 재생·합성 데이터를 구분해 관리합니다.</p></section>
    <DeviceInventory />
    <footer>연결 상태는 수신된 관측으로 판단합니다. 실제 장비·AI 실행의 수용시험은 별도로 진행합니다.</footer>
  </main>;
}
