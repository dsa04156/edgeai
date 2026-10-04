import Link from "next/link";
import { VirtualDeviceRegistry } from "./virtual-device-registry";

export default function VirtualDevices() {
  return <main>
    <header><Link className="brand" href="/">EdgeAI</Link><span className="stage">M6 · VD 관리</span></header>
    <nav className="section-nav" aria-label="관리 메뉴"><Link href="/profiles">Profile 관리</Link><Link href="/devices">장치·노드 관리</Link><Link href="/virtual-devices" aria-current="page">가상 장치 관리</Link><Link href="/workflows">워크플로·실행 관리</Link><Link href="/audit">감사 기록</Link></nav>
    <section><p className="eyebrow">VIRTUAL DEVICES</p><h1>원본이 바뀌어도 같은 가상 장치로 관리합니다.</h1>
      <p className="intro">VD 규격에 맞는 원본 장치를 연결하고, 변경 이력을 보존합니다. 실행 시작·교체·종료를 요청하고 실제 준비 상태와 실행 세대 이력을 확인하세요.</p></section>
    <VirtualDeviceRegistry />
    <footer>등록됨은 실행 준비 완료를 뜻하지 않습니다. 원본 장치의 데이터 수신 상태는 장치 관리에서 확인하세요.</footer>
  </main>;
}
