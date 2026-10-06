import { SectionNav } from "../components/section-nav";
import Link from "next/link";
import { VirtualDeviceRegistry } from "./virtual-device-registry";

export const dynamic = "force-dynamic";

export default function VirtualDevices() {
  return <main>
    <header><Link className="brand" href="/">EdgeAI</Link><span className="stage">Edge AI Platform</span></header>
    <SectionNav current="/virtual-devices" />
    <section><p className="eyebrow">VIRTUAL DEVICES</p><h1>원본이 바뀌어도 같은 가상 장치로 관리합니다.</h1>
      <p className="intro">VD 규격에 맞는 원본 장치를 연결하고, 변경 이력을 보존합니다. 실행 시작·교체·종료를 요청하고 실제 준비 상태와 실행 세대 이력을 확인하세요.</p></section>
    <VirtualDeviceRegistry />
    <footer>등록됨은 실행 준비 완료를 뜻하지 않습니다. 원본 장치의 데이터 수신 상태는 장치 관리에서 확인하세요.</footer>
  </main>;
}
